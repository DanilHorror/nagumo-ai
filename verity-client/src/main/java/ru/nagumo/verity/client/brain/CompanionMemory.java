package ru.nagumo.verity.client.brain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.nagumo.verity.client.config.NagumoClientConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Долгосрочная память компаньона (обсуждение 15.09.2026) — отдельная
 * на каждый сервер/мир, персистентная (переживает выход из мира и перезапуск клиента,
 * в отличие от прежнего чисто оперативного {@code BrainEngine.history}).
 *
 * Два слоя, как договорились:
 * 1. "Живая" история — последние {@link #LIVE_TURNS} обменов, дословно, персистится на диск.
 * 2. Долгосрочная память ({@link #longTermMemory}) — когда обмен вытесняется из живой
 *    истории, он не выбрасывается, а АСИНХРОННО (отдельный executor, не executor
 *    {@code BrainEngine} — чтобы не задерживать ответ игроку) сжимается той же Ollama
 *    в компактное резюме и сливается с уже накопленным. По ОДНОМУ вытесненному обмену
 *    за раз, не пачками (решение 15.09.2026).
 *
 * Если сжатие не удалось (Ollama недоступна в этот момент) — вытесненные реплики
 * НЕ теряются, остаются в {@link MemoryFile#pendingSummarization} до следующей удачной
 * попытки; отдельный фоновый таймер раз в {@link #RETRY_INTERVAL} пробует снова,
 * независимо от того, говорит ли игрок с компаньоном прямо сейчас (решение 15.09.2026).
 *
 * Само резюме тоже не бесконечно — если после очередного слияния превышает
 * {@link #SUMMARY_CHAR_LIMIT}, сжимается ЕЩЁ РАЗ отдельным вызовом (решение 15.09.2026,
 * иначе через месяцы игры резюме унаследует ту же проблему раздутого контекста, которую
 * должно было решать).
 *
 * Формат хранения — тот же принцип, что у {@code NagumoClientConfig} (простой JSON через
 * Gson в config-папке), но с АТОМАРНОЙ записью (временный файл + rename) — потеря истории
 * диалога при вылете игры посреди записи обиднее, чем потеря настроек, а атомарность
 * дёшева (решение 15.09.2026).
 */
public final class CompanionMemory {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Столько ПОЛНЫХ обменов (user+assistant) держим дословно — как и раньше в BrainEngine. */
    private static final int LIVE_TURNS = 16;
    private static final int SUMMARY_CHAR_LIMIT = 4000;
    private static final Duration RETRY_INTERVAL = Duration.ofMinutes(1);

    private record ChatMessage(String role, String content) {
    }

    /** Форма файла на диске — простые публичные поля, как и у NagumoClientConfig. */
    private static final class MemoryFile {
        String longTermMemory = "";
        List<ChatMessage> recentHistory = new ArrayList<>();
        /** Вытеснены из recentHistory, но ещё не успешно сжаты в longTermMemory. */
        List<ChatMessage> pendingSummarization = new ArrayList<>();
    }

    /**
     * Отдельный от {@code BrainEngine.EXECUTOR} поток — вся работа с памятью (диск +
     * фоновое сжатие) не должна задерживать генерацию очередного ответа игроку
     * (решение 15.09.2026: "асинхронно"). Один поток достаточно — операции с памятью
     * не параллелятся между собой, только с основным потоком brain.
     */
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nagumo-memory");
        t.setDaemon(true);
        return t;
    });

    private static volatile String currentServerKey;
    private static volatile MemoryFile state = new MemoryFile();
    private static final AtomicBoolean warnedSummarizeDown = new AtomicBoolean(false);

    static {
        long seconds = RETRY_INTERVAL.toSeconds();
        EXECUTOR.scheduleWithFixedDelay(CompanionMemory::trySummarizePending, seconds, seconds, TimeUnit.SECONDS);
    }

    private CompanionMemory() {
    }

    /** Вызывать на вход в мир (VerityClient, ClientPlayConnectionEvents.JOIN) с уже определённым ключом. */
    public static void load(String serverKey) {
        EXECUTOR.execute(() -> {
            currentServerKey = serverKey;
            state = readFromDisk(serverKey);
            LOGGER.info("[NAGUMO-MEMORY] память загружена для \"{}\": {} обменов, резюме {} симв., "
                            + "{} несжатых реплик в очереди",
                    serverKey, state.recentHistory.size() / 2, state.longTermMemory.length(),
                    state.pendingSummarization.size());
        });
    }

    /** Вызывать на выход из мира (DISCONNECT) — сохраняет и освобождает состояние. */
    public static void unload() {
        EXECUTOR.execute(() -> {
            saveToDiskInternal();
            currentServerKey = null;
            state = new MemoryFile();
        });
    }

    /**
     * Определить ключ памяти по текущему подключению — память отдельно на каждый
     * сервер/мир И на каждого игрока (решение 15.09.2026, продолжение того же
     * обсуждения, что и остальная работа над памятью в этот день). Раньше ключ был
     * только по серверу — если на одном клиенте когда-либо заходили под разными
     * Minecraft-аккаунтами (в т.ч. офлайн/альт-аккаунтами — актуально и для тестов
     * через {@code --username}), все они делили одну долгосрочную память на этом
     * сервере (подтверждено живыми тестами того же дня — ТЗ, "находка 2":
     * несколько тестовых {@code --username} на {@code 127.0.0.1:25565} делили один
     * файл {@code 127.0.0.1_25565.json} и накапливали чужую историю).
     *
     * {@code Minecraft.getCurrentServer()} — ПРОВЕРЕНО живыми тестами 15.09.2026:
     * несколько подключений подряд через {@code --quickPlayMultiplayer} на
     * {@code 127.0.0.1:25565} стабильно давали ключ {@code "127.0.0.1_25565..."}, не
     * {@code "singleplayer"} — для этого сценария подключения (тот, что реально
     * тестировался) риск null/пустого {@code ServerData} не подтвердился. Прямой
     * Direct Connect через GUI-кнопку отдельно не проверялся. Одиночная игра (нет
     * ServerData) — фиксированный ключ "singleplayer" (голос там и так не работает,
     * раздел 9 ТЗ, но текстовые команды в принципе могут — не роняем на всякий случай).
     *
     * {@code client.player.getUUID()} — на момент вызова из
     * {@code ClientPlayConnectionEvents.JOIN} (единственная точка вызова, см.
     * {@code VerityClient}) игрок уже должен существовать, но defensive null-check по
     * аналогии с уже существующей проверкой на {@code server == null}, на случай
     * более раннего/неожиданного вызова. В offline-режиме UUID — стабильный
     * {@code UUID.nameUUIDFromBytes} от ника, то есть ключ стабилен, пока ник не
     * меняется — корректное поведение для памяти.
     *
     * Простейший вариант — не переделывать {@link #memoryPath}/{@link #memoryDir}
     * (остаются flat-файлами в {@code config/nagumo-memory/}), а просто склеить
     * {@code <очищенный-server-key>_<playerUUID>.json} прямо здесь.
     */
    public static String resolveMemoryKey(Minecraft client) {
        var server = client.getCurrentServer();
        String rawServer = (server != null && server.ip != null && !server.ip.isBlank()) ? server.ip : "singleplayer";
        String cleanedServer = rawServer.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.-]", "_");
        if (cleanedServer.isBlank()) {
            cleanedServer = "unknown";
        }

        var player = client.player;
        String playerId = player != null ? player.getUUID().toString() : "unknown";
        return cleanedServer + "_" + playerId;
    }

    // ---- Используется BrainEngine на каждый вопрос игрока (вызывается на BrainEngine.EXECUTOR) ----

    public static synchronized void appendUser(String text) {
        state.recentHistory.add(new ChatMessage("user", text));
    }

    /** Откат последнего user-сообщения, если ответ не удался (как раньше history.removeLast()). */
    public static synchronized void removeLastUserIfPresent() {
        List<ChatMessage> h = state.recentHistory;
        if (!h.isEmpty() && "user".equals(h.get(h.size() - 1).role())) {
            h.remove(h.size() - 1);
        }
    }

    public static synchronized void appendAssistantAndTrim(String text) {
        state.recentHistory.add(new ChatMessage("assistant", text));
        trimLocked();
        EXECUTOR.execute(CompanionMemory::saveToDiskInternal);
    }

    /** {role, content} пары для сборки messages в Ollama-запросе — без завязки на приватный тип. */
    public static synchronized List<String[]> historySnapshot() {
        List<String[]> out = new ArrayList<>();
        for (ChatMessage m : state.recentHistory) {
            out.add(new String[]{m.role(), m.content()});
        }
        return out;
    }

    public static String longTermMemory() {
        return state.longTermMemory;
    }

    /** Вызывающий уже держит monitor CompanionMemory.class (appendAssistantAndTrim). */
    private static void trimLocked() {
        // По одному обмену за раз (решение 15.09.2026: не пачками), самые старые первыми.
        while (state.recentHistory.size() > LIVE_TURNS * 2) {
            state.pendingSummarization.add(state.recentHistory.remove(0));
            if (!state.recentHistory.isEmpty()) {
                state.pendingSummarization.add(state.recentHistory.remove(0));
            }
        }
        if (!state.pendingSummarization.isEmpty()) {
            EXECUTOR.execute(CompanionMemory::trySummarizePending);
        }
    }

    // ---- Фоновое сжатие (только на EXECUTOR — свой поток, не блокирует ответ игроку) ----

    private static void trySummarizePending() {
        String key = currentServerKey;
        List<ChatMessage> pending;
        String previousSummary;
        synchronized (CompanionMemory.class) {
            if (key == null || state.pendingSummarization.isEmpty()) {
                return;
            }
            pending = List.copyOf(state.pendingSummarization);
            previousSummary = state.longTermMemory;
        }

        String updatedSummary;
        try {
            updatedSummary = summarizeViaOllama(previousSummary, pending);
        } catch (Exception e) {
            // Решение 15.09.2026: держим реплики сырыми до успеха, не теряем — просто
            // ждём следующего тика таймера (см. static-блок выше).
            warnOnce("не удалось сжать историю в долгосрочную память (" + e.getMessage()
                    + ") — реплики не потеряны, попробую снова через " + RETRY_INTERVAL.toMinutes() + " мин.");
            return;
        }
        if (updatedSummary == null || updatedSummary.isBlank()) {
            return;
        }
        updatedSummary = updatedSummary.trim();

        // Резюме само не бесконечно (решение 15.09.2026) — если разрослось, сжать ЕЩЁ РАЗ.
        if (updatedSummary.length() > SUMMARY_CHAR_LIMIT) {
            try {
                updatedSummary = recompressSummary(updatedSummary);
            } catch (Exception e) {
                LOGGER.warn("[NAGUMO-MEMORY] не удалось сжать разросшееся резюме ({} симв.), оставляю как есть: {}",
                        updatedSummary.length(), e.getMessage());
            }
        }

        String finalSummary = updatedSummary;
        synchronized (CompanionMemory.class) {
            if (!java.util.Objects.equals(currentServerKey, key)) {
                return; // игрок уже вышел/зашёл на другой сервер, пока считалось сжатие
            }
            state.longTermMemory = finalSummary;
            // Убираем именно то, что успешно сжали (могло что-то добавиться за время вызова).
            int n = Math.min(pending.size(), state.pendingSummarization.size());
            state.pendingSummarization.subList(0, n).clear();
        }
        warnedSummarizeDown.set(false);
        saveToDiskInternal();
        LOGGER.info("[NAGUMO-MEMORY] память сжата и сохранена ({} симв.)", finalSummary.length());
    }

    private static String summarizeViaOllama(String previousSummary, List<ChatMessage> toCompress) throws Exception {
        NagumoClientConfig config = NagumoClientConfig.get();
        if (config.modelTier == ModelTier.OFF) {
            throw new IllegalStateException("компаньон выключен (modelTier = OFF)");
        }
        String model = ModelManager.ollamaModelFor(config.modelTier);
        if (!ModelManager.ensureModelAvailable(config.modelTier)) {
            throw new IllegalStateException("модель " + model + " недоступна");
        }

        StringBuilder dialogue = new StringBuilder();
        for (ChatMessage m : toCompress) {
            dialogue.append("user".equals(m.role()) ? "Игрок: " : "Компаньон: ").append(m.content()).append('\n');
        }

        String prompt = "Ты сжимаешь историю диалога ИИ-компаньона в Minecraft с игроком в компактную "
                + "долгосрочную память. Отбрасывай светскую болтовню (приветствия, шутки без сути) — оставляй "
                + "только конкретные факты, договорённости, имена, текущие проекты игрока. Пиши по-русски, "
                + "кратко, безоценочно, от третьего лица (\"игрок делает X\", не \"я/ты\"). "
                + (previousSummary.isBlank() ? "" : "Уже накопленная память: " + previousSummary + "\n\n")
                + "Новый фрагмент диалога для добавления в память:\n" + dialogue
                + "\nВерни ОБНОВЛЁННУЮ память целиком (старое + новое слитно, без повторов), без пояснений от "
                + "себя — только сам текст памяти.";

        return BrainEngine.callOllamaRaw(model, prompt);
    }

    private static String recompressSummary(String tooLong) throws Exception {
        NagumoClientConfig config = NagumoClientConfig.get();
        String model = ModelManager.ollamaModelFor(config.modelTier);
        String prompt = "Сократи этот текст долгосрочной памяти ИИ-компаньона примерно вдвое: убери повторы и "
                + "второстепенное, сохрани ВСЕ конкретные факты (имена, договорённости, проекты игрока). "
                + "Верни только сжатый текст, без пояснений от себя.\n\n" + tooLong;
        return BrainEngine.callOllamaRaw(model, prompt);
    }

    private static void warnOnce(String message) {
        if (warnedSummarizeDown.compareAndSet(false, true)) {
            LOGGER.warn("[NAGUMO-MEMORY] {}", message);
        }
    }

    // ---- Диск ----

    private static Path memoryDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("nagumo-memory");
    }

    private static Path memoryPath(String serverKey) {
        return memoryDir().resolve(serverKey + ".json");
    }

    private static MemoryFile readFromDisk(String serverKey) {
        Path path = memoryPath(serverKey);
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                MemoryFile loaded = GSON.fromJson(json, MemoryFile.class);
                if (loaded != null) {
                    if (loaded.longTermMemory == null) {
                        loaded.longTermMemory = "";
                    }
                    if (loaded.recentHistory == null) {
                        loaded.recentHistory = new ArrayList<>();
                    }
                    if (loaded.pendingSummarization == null) {
                        loaded.pendingSummarization = new ArrayList<>();
                    }
                    return loaded;
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("[NAGUMO-MEMORY] не удалось прочитать {} — начинаю с пустой памяти: {}", path, e.toString());
            }
        }
        return new MemoryFile();
    }

    /**
     * Атомарная запись (временный файл + rename) — решение 15.09.2026: историю диалога
     * жальче терять при вылете игры посреди записи, чем настройки (NagumoClientConfig
     * пишет напрямую), а атомарность здесь ничего не стоит.
     */
    private static synchronized void saveToDiskInternal() {
        String key = currentServerKey;
        MemoryFile snapshot = state;
        if (key == null) {
            return;
        }
        Path path = memoryPath(key);
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(memoryDir());
            Files.writeString(tmp, GSON.toJson(snapshot), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("[NAGUMO-MEMORY] не удалось сохранить {}: {}", path, e.toString());
        }
    }

    // ---- Команды /nagumosettings forget / memory (решение 15.09.2026: клиентское дерево) ----

    /** Синхронно на вызывающем потоке (командный поток клиента) — быстрая операция, не критично. */
    public static synchronized void forgetCurrentServer() {
        state = new MemoryFile();
        EXECUTOR.execute(CompanionMemory::saveToDiskInternal);
    }

    public static synchronized String memorySummaryForDisplay() {
        return state.longTermMemory.isBlank()
                ? "Пока не помню ничего особенного про этот сервер."
                : state.longTermMemory;
    }
}
