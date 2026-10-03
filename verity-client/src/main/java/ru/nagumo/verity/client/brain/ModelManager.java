package ru.nagumo.verity.client.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Готовит веса локальной LLM (раздел 6/11 ТЗ, шаг 3) через локально установленную
 * Ollama (http://127.0.0.1:11434) — раздел 6 ТЗ прямо говорит, что рассчитывать на
 * готовую Ollama у массового игрока нереалистично для будущего "настоящего" движка
 * (там нужны биндинги к llama.cpp внутри самого мода), но для ЭТОГО прохода (раздел
 * 11 ТЗ, шаг 5 — первый реальный вызов LLM) используем то, что уже есть на машине
 * разработки, как явно указано в задаче. Замена на встроенный движок — отдельная
 * задача позже, ModelManager/BrainEngine спроектированы как единственная точка,
 * которую тогда придётся поменять (раздел 6 ТЗ: "модуль brain остаётся изолированным").
 *
 * Конкретные модели на каждый уровень ({@link #OLLAMA_MODELS}) — семейство
 * Qwen2.5-instruct (заметно лучше держит русский язык, чем мелкие Llama — GigaAM
 * распознаёт только русскую речь, и компаньон всегда отвечает по-русски, раздел 8
 * ТЗ). Теги проверены вживую ({@code ollama list} на машине разработки и список
 * тегов официальной библиотеки Ollama), не выдуманы: 1.5b/3b/7b — реальные размеры
 * из этого семейства, ближайшие к ориентирам задачи (mini ~1.5B, lite ~3B, standard
 * ~7B). Без суффикса это дефолтный instruct-тег с Q4_K_M-квантованием — кроме LITE,
 * см. {@link #OLLAMA_MODELS}, у него явный {@code -q8_0} по итогам расследования
 * "находки 2" (ТЗ, 15.09.2026).
 */
public final class ModelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");

    private static final URI TAGS_ENDPOINT = URI.create("http://127.0.0.1:11434/api/tags");
    private static final URI PULL_ENDPOINT = URI.create("http://127.0.0.1:11434/api/pull");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    /** Скачивание весов — минуты, не секунды; таймаута тут по сути "нет" в разумных пределах. */
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(30);

    /**
     * LITE — {@code qwen2.5:3b-instruct-q8_0}, НЕ дефолтный {@code qwen2.5:3b} (Q4_K_M),
     * с 15.09.2026 ("находка 2" в ТЗ, пункт 3 расследования). A/B на клиенте
     * разработки при прочих равных (тот же факт с точным числом ингредиентов в
     * промпте, тот же жёсткий промпт, та же temperature, ЧИСТАЯ история диалога —
     * без этого условия результат искажён, см. ТЗ): Q4_K_M давал верный
     * материал, но терял точное число ("Печь крафтишь из булыжника", без "x8"); тот
     * же 3B в Q8_0 — материал И число верно, воспроизведено дважды подряд.
     * Квантование, не размер модели — STANDARD (7B, тоже full/higher precision)
     * с той же чистой историей тоже стабильно верен, а 3B/Q4_K_M со STANDARD-уровня
     * промптом всё равно терял число. Скачивание тяжелее (~3.3 ГБ вместо ~2.2 ГБ) —
     * сознательный компромисс ради корректности фактов, а не гадание.
     */
    private static final Map<ModelTier, String> OLLAMA_MODELS = Map.of(
            ModelTier.MINI, "qwen2.5:1.5b",
            ModelTier.LITE, "qwen2.5:3b-instruct-q8_0",
            ModelTier.STANDARD, "qwen2.5:7b"
    );

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** Отдельный однопоточный executor под фоновую подготовку модели, вызванную с игрового/командного потока. */
    private static final ExecutorService PREP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nagumo-model-prep");
        t.setDaemon(true);
        return t;
    });

    /** true, пока Ollama недоступна — не спамить варнингом в лог на каждую попытку. */
    private static final AtomicBoolean warnedDown = new AtomicBoolean(false);

    private ModelManager() {
    }

    /** Тег модели Ollama для уровня, или {@code null} для {@link ModelTier#OFF}. */
    public static String ollamaModelFor(ModelTier tier) {
        return OLLAMA_MODELS.get(tier);
    }

    /**
     * Убедиться, что веса для {@code tier} доступны локально: проверяет
     * {@code GET /api/tags}, при отсутствии — {@code POST /api/pull} со стримом
     * прогресса в лог. БЛОКИРУЮЩИЙ сетевой вызов (потенциально на минуты при первом
     * скачивании) — вызывать только из фонового потока, никогда с игрового/командного.
     *
     * @return {@code true}, если модель доступна (была уже, либо скачалась сейчас);
     *         {@code false} — Ollama недоступна или скачивание не удалось (см. лог).
     */
    public static boolean ensureModelAvailable(ModelTier tier) {
        if (tier == ModelTier.OFF) {
            return true; // нечего готовить — компаньон выключен
        }
        String model = ollamaModelFor(tier);
        try {
            if (isModelPresent(model)) {
                warnedDown.set(false);
                return true;
            }
            LOGGER.info("[NAGUMO-MODEL] модель {} (уровень {}) не найдена локально — качаю через "
                    + "ollama pull, это может занять время в зависимости от размера и скорости сети", model, tier);
            boolean ok = pullModel(model);
            if (ok) {
                warnedDown.set(false);
                LOGGER.info("[NAGUMO-MODEL] модель {} готова", model);
            } else {
                LOGGER.warn("[NAGUMO-MODEL] не удалось скачать модель {} — см. лог выше", model);
            }
            return ok;
        } catch (IOException e) {
            warnOnce("Ollama недоступна на 127.0.0.1:11434 (" + e.getMessage() + ") — запусти: ollama serve");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * То же самое, но в фоне — не блокирует вызывающий поток (для вызова из
     * {@code /nagumosettings model ...}, который выполняется на клиентском командном
     * потоке и не должен зависать на минуты при скачивании многогигабайтных весов).
     */
    public static void ensureModelAvailableAsync(ModelTier tier) {
        PREP_EXECUTOR.execute(() -> ensureModelAvailable(tier));
    }

    private static boolean isModelPresent(String model) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(TAGS_ENDPOINT).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET /api/tags -> HTTP " + response.statusCode());
        }
        JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!obj.has("models")) {
            return false;
        }
        for (JsonElement el : obj.getAsJsonArray("models")) {
            JsonObject m = el.getAsJsonObject();
            if (m.has("name") && model.equals(m.get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code stream:true} — NDJSON, одна JSON-строка на событие прогресса. Логируем
     * не каждую строку (их сотни на крупную модель), а по шагу ~10% на каждый digest
     * (кусок файла), плюс смену статуса ("pulling manifest", "verifying sha256
     * digest", "success" и т.п.) — простой лог, как и просила задача (без прогресс-бара).
     */
    private static boolean pullModel(String model) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", true);

        HttpRequest request = HttpRequest.newBuilder(PULL_ENDPOINT)
                .timeout(PULL_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<Stream<String>> response = HTTP.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() / 100 != 2) {
            LOGGER.warn("[NAGUMO-MODEL] POST /api/pull ({}) -> HTTP {}", model, response.statusCode());
            return false;
        }

        Map<String, Integer> lastLoggedPercent = new HashMap<>();
        boolean[] success = {false};
        String[] lastStatus = {""};
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                JsonObject obj;
                try {
                    obj = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    return; // неполная/битая строка стрима — пропускаем, не роняем скачивание
                }
                String status = obj.has("status") ? obj.get("status").getAsString() : "";
                if (obj.has("total") && obj.has("completed")) {
                    long total = obj.get("total").getAsLong();
                    long completed = obj.get("completed").getAsLong();
                    String digest = obj.has("digest") ? obj.get("digest").getAsString() : status;
                    int percent = total > 0 ? (int) (completed * 100 / total) : 0;
                    int lastPercent = lastLoggedPercent.getOrDefault(digest, -10);
                    if (percent - lastPercent >= 10 || percent == 100) {
                        LOGGER.info("[NAGUMO-MODEL] {}: {} — {}%", model, status, percent);
                        lastLoggedPercent.put(digest, percent);
                    }
                } else if (!status.equals(lastStatus[0])) {
                    LOGGER.info("[NAGUMO-MODEL] {}: {}", model, status);
                    lastStatus[0] = status;
                }
                if ("success".equals(status)) {
                    success[0] = true;
                }
            });
        }
        return success[0];
    }

    private static void warnOnce(String message) {
        if (warnedDown.compareAndSet(false, true)) {
            LOGGER.warn("[NAGUMO-MODEL] {}", message);
        }
    }
}
