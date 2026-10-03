package ru.nagumo.verity.client.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ru.nagumo.verity.client.config.NagumoClientConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ИИ-мозг компаньона (раздел 6 ТЗ) — реальный клиентский инференс вместо эхо-заглушки
 * (раздел 11 ТЗ, шаг 5). Намеренно клиентский, а не серверный (раздел 3/6 ТЗ:
 * экономия GPU) — вызывается сервером через сетевой round-trip
 * ({@code BrainRequestPayload} → сюда → {@code BrainReplyPayload}), см.
 * {@code VerityClient} и {@code NagumoVoicechatPlugin} (verity-server).
 *
 * Движок инференса — локально установленная Ollama (http://127.0.0.1:11434, REST
 * {@code /api/chat}). Раздел 6 ТЗ справедливо отмечает, что рассчитывать на готовую
 * Ollama у массового игрока нереалистично для итоговой версии (там нужен встроенный
 * движок вроде llama.cpp-биндингов прямо в моде) — но именно этот способ явно
 * указан в задаче для ПЕРВОГО прохода реального вызова LLM; замена движка — заменить
 * только {@link #callOllama}, остальной класс (история, системный промпт, сетевой
 * приём/ответ) не завязан на конкретный движок.
 */
public final class BrainEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");

    private static final URI CHAT_ENDPOINT = URI.create("http://127.0.0.1:11434/api/chat");
    /** Модель может думать долго (особенно standard на слабом железе, плюс холодная загрузка весов в VRAM). */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private static final String FALLBACK_REPLY =
            "Прости, сейчас не получается подумать — проверь, что локальная Ollama запущена "
                    + "(ollama serve), и попробуй ещё раз.";
    private static final String DISABLED_REPLY = "Компаньон сейчас выключен в настройках.";

    /**
     * Раздел "находка 2" (ТЗ, обсуждение 15.09.2026) — дефолтная
     * temperature модели давала разброс, из-за которого модель охотно "довыдумывала"
     * детали рецепта даже при верном факте в контексте. Низкая temperature — более
     * детерминированные, "прижатые к фактам" ответы; для обычного бытового общения
     * (не только фактологичных вопросов о рецептах) это тоже уместно — компаньон и так
     * не творческий помощник, ему не нужна высокая вариативность. 0.2 — низ диапазона,
     * который сам предложил пользователь (0.2-0.3), не выдумано наугад.
     */
    private static final double CHAT_TEMPERATURE = 0.2;

    /**
     * Раздел "фикс 2, попытка 2" (обсуждение 16.09.2026, ТЗ) —
     * просить модель САМУ решать "знаю/не знаю" не сработало ни на LITE, ни на STANDARD
     * (одна строчка среди длинного общего промпта игнорировалась). Вместо этого решаем
     * в коде ДО вызова Ollama: если вопрос похож на запрос про конкретную вещь
     * ({@link #looksLikeItemQuestion}) И {@link GameKnowledgeIndex#search} не нашёл
     * ничего релевантного — используем отдельный, узкий промпт
     * ({@link #buildUnknownItemPrompt}) вместо общего {@link #buildSystemPrompt}.
     *
     * Список ключевых слов — простая регистронезависимая проверка по подстроке, НЕ
     * ML, сознательно неполный (не покрывает все формулировки вопроса про вещь) —
     * компромисс, а не баг, как и попросил пользователь.
     */
    private static final Set<String> ITEM_QUESTION_KEYWORDS = Set.of(
            "рецепт", "крафт", "скрафт", "из чего", "как сделать", "как создать",
            "ингредиент", "нужно для"
    );

    private static final Map<String, String> STYLE_DESCRIPTIONS = Map.of(
            "friendly", "Дружелюбный и живой — тёплый, поддерживающий тон, лёгкий юмор уместен.",
            "ironic", "Ироничный и саркастичный — подкалывай по-доброму, не переходи в настоящую грубость.",
            "business", "Нейтрально-деловой — сухо и по делу, без эмоциональной окраски и лишних слов.",
            "reserved", "Сдержанный и лаконичный — минимум слов, без длинных пояснений, если не просят."
    );

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * Один общий executor под всю работу brain (а не отдельные пулы на разные этапы,
     * как в серверном STT/TTS) — на клиенте ровно один пользователь и ровно один
     * запрос в моменте (пайплайн последовательный, раздел 5 ТЗ), разделять пулы не для
     * чего. Один поток, а не cached-pool — запросы и так идут по одному за раз, а
     * последовательное выполнение само по себе гарантирует, что история (см. ниже)
     * не читается/пишется параллельно из двух вызовов сразу.
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nagumo-brain");
        t.setDaemon(true);
        return t;
    });

    /** true, пока Ollama недоступна/не отвечает — не спамить варнингом в лог на каждую реплику. */
    private static final AtomicBoolean warnedDown = new AtomicBoolean(false);

    private BrainEngine() {
    }

    /**
     * Асинхронно сгенерировать ответ на распознанную реплику и передать его в
     * {@code onReply} (вызывается на потоке {@link #EXECUTOR}, не на игровом/сетевом —
     * вызывающий код должен сам быть готов к этому, как и при отправке пакета в ответ).
     */
    public static void generateReplyAsync(String recognizedText, Consumer<String> onReply) {
        EXECUTOR.execute(() -> {
            String reply;
            try {
                reply = generateReply(recognizedText);
            } catch (Throwable t) {
                LOGGER.error("[NAGUMO-BRAIN] необработанная ошибка генерации ответа: {}", t.getMessage(), t);
                reply = FALLBACK_REPLY;
            }
            onReply.accept(reply);
        });
    }

    private static String generateReply(String recognizedText) {
        NagumoClientConfig config = NagumoClientConfig.get();
        ModelTier tier = config.modelTier;
        if (tier == ModelTier.OFF) {
            return DISABLED_REPLY;
        }
        String model = ModelManager.ollamaModelFor(tier);

        // Гарантия наличия весов перед каждым вызовом (дёшево, если уже скачаны — один
        // GET /api/tags) — а не только в момент смены уровня в настройках, на случай
        // если /nagumosettings model выбрали раньше, чем скачивание успело завершиться,
        // либо конфиг был сохранён с этим уровнем ещё до первого реального вызова brain.
        if (!ModelManager.ensureModelAvailable(tier)) {
            return FALLBACK_REPLY;
        }

        // История теперь персистентная и отдельная на каждый сервер/мир (обсуждение с
        // пользователем 15.09.2026, см. CompanionMemory) — сама следит за лимитом
        // HISTORY_TURNS и асинхронно сжимает вытесненное в долгосрочную память.
        CompanionMemory.appendUser(recognizedText);

        // База знаний по предметам/рецептам (и ванильным, и модовым — GameKnowledgeIndex
        // не различает происхождение, см. его javadoc) — посчитана здесь, а не внутри
        // buildSystemPrompt, потому что от результата зависит, каким из двух промптов
        // отвечать (см. ITEM_QUESTION_KEYWORDS выше).
        List<String> knowledge = GameKnowledgeIndex.search(recognizedText);
        // Полезно при диагностике (пригодился 15.09.2026 для подтверждения гипотезы
        // "индекс рецептов пуст в момент вопроса" — см. ТЗ, "Живой поведенческий
        // тест") — но на каждый вопрос спамить в лог по умолчанию незачем, поэтому debug.
        LOGGER.debug("[NAGUMO-KNOWLEDGE] запрос=\"{}\" -> найдено {} записей: {}",
                recognizedText, knowledge.size(), knowledge);

        String systemPrompt;
        if (knowledge.isEmpty() && looksLikeItemQuestion(recognizedText)) {
            LOGGER.info("[NAGUMO-BRAIN] узкая ветка «не знаю»: вопрос похож на запрос про "
                    + "конкретную вещь, но фактов не найдено (\"{}\")", recognizedText);
            systemPrompt = buildUnknownItemPrompt(config);
        } else {
            systemPrompt = buildSystemPrompt(config, knowledge);
        }

        try {
            String reply = callOllama(model, systemPrompt);
            if (reply == null || reply.isBlank()) {
                CompanionMemory.removeLastUserIfPresent(); // не засорять историю пустым/неудачным обменом
                warnOnce("Ollama вернула пустой ответ — модель " + model + " загружена, но ответ пуст");
                return FALLBACK_REPLY;
            }
            reply = reply.trim();
            CompanionMemory.appendAssistantAndTrim(reply);
            warnedDown.set(false); // brain снова отвечает
            return reply;
        } catch (IOException e) {
            CompanionMemory.removeLastUserIfPresent();
            warnOnce("Ollama недоступна на 127.0.0.1:11434 (" + e.getMessage() + ") — запусти: ollama serve");
            return FALLBACK_REPLY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CompanionMemory.removeLastUserIfPresent();
            return FALLBACK_REPLY;
        } catch (RuntimeException e) {
            CompanionMemory.removeLastUserIfPresent();
            warnOnce("не удалось разобрать ответ Ollama: " + e.getMessage());
            return FALLBACK_REPLY;
        }
    }

    /**
     * Личность и стиль — раздел 8 ТЗ: без придуманной предыстории (открыто ИИ), один
     * из 4 пресетов характера (раздел 11 ТЗ, шаг 5 — теперь клиентская настройка,
     * см. {@link NagumoClientConfig#style}), обязательно по-русски (GigaAM распознаёт
     * только русскую речь, раздел 5 ТЗ), и не растянуто — ответ уходит в TTS (Piper,
     * тоже на сервере), длинный ответ неудобно слушать.
     */
    private static String buildSystemPrompt(NagumoClientConfig config, List<String> knowledge) {
        String styleDescription = STYLE_DESCRIPTIONS.getOrDefault(config.style, STYLE_DESCRIPTIONS.get(NagumoClientConfig.DEFAULT_STYLE));
        String prompt = "Ты — голосовой ИИ-компаньон в Minecraft по имени " + config.companionName + ". "
                + "Ты открыто ИИ-ассистент, без придуманной предыстории или легенды — если спросят, кто ты, "
                + "прямо говори, что ты ИИ. "
                + "Стиль общения: " + styleDescription + " "
                + "Отвечай ТОЛЬКО на русском языке, независимо от языка вопроса. "
                + "Формат ответа: короткие живые реплики для бытового общения, более развёрнутые объяснения "
                + "для игровых и технических вопросов — но твой ответ будет озвучен голосом (TTS), поэтому "
                + "говори разговорно, по существу и не растягивай: без markdown, без списков, без нумерации. "
                + "Ты не выполняешь действия в игровом мире (крафт, бой, добычу и т.п.) — только разговариваешь "
                + "и подсказываешь по игре и установленным у игрока модам. "
                // Раздел "находка 2" (ТЗ, расследование "верстак из цветов и
                // кактуса", 16.09.2026) — независимо от того, нашлись ли ниже факты по
                // теме вопроса: если точного ответа не знаешь (или знаешь только
                // приблизительно), честно скажи об этом, а не выдумывай правдоподобный,
                // но, возможно, неверный рецепт/деталь — игрок скорее простит "не знаю",
                // чем поверит в выдумку и облажается в игре.
                + "Если тебя спросят про точный рецепт крафта, характеристики предмета или другую конкретную "
                + "игровую деталь, а ты не знаешь точного ответа (ни из своих знаний, ни из фактов ниже, если "
                + "они есть) — честно скажи, что не знаешь точно или не уверен, вместо того чтобы придумывать "
                + "правдоподобный, но возможно неверный ответ.";

        // База знаний по предметам/рецептам (и ванильным, и модовым — GameKnowledgeIndex
        // не различает происхождение, см. его javadoc) — подмешиваем только релевантные
        // текущему вопросу записи, не весь индекс целиком (промпт иначе раздуется).
        // Сам поиск теперь считается в generateReply (нужен раньше — определяет, какой
        // из двух промптов строить), сюда приходит уже готовый список.
        if (!knowledge.isEmpty()) {
            // Раньше формулировка была мягкой рекомендацией ("используй, только если
            // релевантны") — модель пользовалась этой лазейкой и отвечала по своим
            // общим (и потенциально неточным именно для этого сервера/этих модов)
            // знаниям о Minecraft, игнорируя верный факт в контексте (воспроизведено
            // трижды подряд живым тестом на разных прогонах, см. ТЗ, "находка
            // 2" — жёсткая формулировка ниже: обсуждение 15.09.2026).
            prompt += " Ниже — точные данные именно этого сервера и установленных на нём модов. Если вопрос "
                    + "игрока относится к тому, что описано ниже, отвечай СТРОГО по этим данным, а не по своим "
                    + "общим знаниям о Minecraft — на этом сервере рецепты/предметы могут отличаться от ванильных "
                    + "или обычных модовых. Не придумывай ингредиенты, количества или предметы, которых здесь нет. "
                    + "Не упоминай, что это список/источник/данные — отвечай так, как будто знаешь это сам: "
                    + String.join("; ", knowledge) + ".";
        }

        // Долгосрочная память об этом конкретном игроке/сервере (обсуждение 15.09.2026,
        // см. CompanionMemory) — компактное резюме прошлых сессий, не сырой лог.
        String longTermMemory = CompanionMemory.longTermMemory();
        if (!longTermMemory.isBlank()) {
            prompt += " Вот что ты помнишь про этого игрока из прошлых разговоров (используй естественно, не "
                    + "зачитывай списком и не упоминай, что это \"из памяти\"): " + longTermMemory;
        }

        return prompt;
    }

    /**
     * Простая регистронезависимая проверка по подстроке на "вопрос похож на запрос про
     * конкретную вещь" ({@link #ITEM_QUESTION_KEYWORDS}) — не ML, список неполный
     * (не покрывает все формулировки), это осознанный компромисс, а не баг.
     */
    private static boolean looksLikeItemQuestion(String recognizedText) {
        String lower = recognizedText.toLowerCase(Locale.ROOT);
        for (String keyword : ITEM_QUESTION_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Узкий промпт на случай "похоже на вопрос про конкретную вещь, но фактов не
     * нашлось" (см. {@link #looksLikeItemQuestion}, javadoc {@link #ITEM_QUESTION_KEYWORDS}).
     * Раньше это была одна строчка среди длинного общего промпта ({@link #buildSystemPrompt})
     * — не сработало ни на LITE, ни на STANDARD (обсуждение 16.09.2026, ТЗ,
     * живой тест дважды подряд подтвердил выдумку несмотря на инструкцию). Здесь
     * признание "не знаю" — ЕДИНСТВЕННАЯ задача модели в этом вызове, не одно правило
     * среди прочих: минимум личности (имя/стиль/язык, чтобы ответ звучал в характере),
     * без общего блока правил и без долгосрочной памяти — они тут не нужны и только
     * отвлекли бы от единственной задачи.
     */
    private static String buildUnknownItemPrompt(NagumoClientConfig config) {
        String styleDescription = STYLE_DESCRIPTIONS.getOrDefault(config.style, STYLE_DESCRIPTIONS.get(NagumoClientConfig.DEFAULT_STYLE));
        return "Ты — голосовой ИИ-компаньон в Minecraft по имени " + config.companionName + ". "
                + "Стиль общения: " + styleDescription + " Отвечай ТОЛЬКО на русском языке. "
                + "Твоя единственная задача сейчас: игрок спросил про что-то конкретное (рецепт, предмет или "
                + "игровую механику), но у тебя нет данных об этом на этом сервере. Честно и по-дружески скажи, "
                + "что точно не знаешь или не уверен — своими словами, в своём обычном стиле, но НЕ называй "
                + "никаких материалов, предметов или количеств, которых нет в данных сервера: не пытайся угадать "
                + "и не ссылайся на общие знания о Minecraft, просто признай, что не знаешь. Коротко — ответ "
                + "будет озвучен голосом (TTS).";
    }

    private static String callOllama(String model, String systemPrompt) throws IOException, InterruptedException {
        JsonArray messages = new JsonArray();
        messages.add(chatMessageJson("system", systemPrompt));
        // История теперь живёт в CompanionMemory (персистентная, своя на каждый сервер/
        // мир, решение 15.09.2026) — здесь только читаем снимок для сборки запроса.
        for (String[] pair : CompanionMemory.historySnapshot()) {
            messages.add(chatMessageJson(pair[0], pair[1]));
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("stream", false);
        // options.temperature — формат подтверждён вживую (curl к локальной Ollama,
        // /api/chat), не по памяти. Только здесь, не в callOllamaRaw — там другая
        // задача (сжатие истории в долгосрочную память), не фактологичный ответ.
        JsonObject options = new JsonObject();
        options.addProperty("temperature", CHAT_TEMPERATURE);
        body.add("options", options);

        return sendChatRequest(body);
    }

    /**
     * Разовый вызов Ollama без истории/системного промпта brain — одно сообщение
     * (роль "user"), весь запрос целиком в {@code prompt}. Используется
     * {@link CompanionMemory} для фонового сжатия истории в долгосрочную память —
     * это отдельная задача от "ответь как компаньон", своя формулировка промпта.
     * Пакетный доступ (не private) — вызывается из {@code CompanionMemory}, тот же пакет.
     */
    static String callOllamaRaw(String model, String prompt) throws IOException, InterruptedException {
        JsonArray messages = new JsonArray();
        messages.add(chatMessageJson("user", prompt));

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("stream", false);

        return sendChatRequest(body);
    }

    private static String sendChatRequest(JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(CHAT_ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("POST /api/chat -> HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!obj.has("message")) {
            return null;
        }
        JsonObject messageObj = obj.getAsJsonObject("message");
        return messageObj.has("content") ? messageObj.get("content").getAsString() : null;
    }

    private static JsonObject chatMessageJson(String role, String content) {
        JsonObject json = new JsonObject();
        json.addProperty("role", role);
        json.addProperty("content", content);
        return json;
    }

    private static void warnOnce(String message) {
        if (warnedDown.compareAndSet(false, true)) {
            LOGGER.warn("[NAGUMO-BRAIN] {}", message);
        }
    }
}
