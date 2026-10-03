package ru.nagumo.verity.server.voice;

import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import ru.nagumo.verity.common.network.BrainReplyPayload;
import ru.nagumo.verity.common.network.BrainRequestPayload;
import ru.nagumo.verity.common.network.NagumoPayloads;
import ru.nagumo.verity.common.network.SetCompanionHudStatePayload;
import ru.nagumo.verity.common.network.ShowTextPayload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Плагин Simple Voice Chat для NAGUMO AI (раздел 5 ТЗ).
 *
 * ОТКАТ 11.09.2026 (см. ТЗ и archive/server-side-voice-revert-2026-09-10/README.md):
 * этот файл раньше уже существовал, был удалён при ошибочном переносе всего
 * голосового пайплайна на клиент вместе с экономией GPU для будущего LLM (которая
 * нужна была ТОЛЬКО для LLM — GigaAM/Piper лёгкие и не требуют GPU). Дословный текст
 * старой версии не сохранился (см. README архива), поэтому файл написан заново, но
 * по тому же словесному описанию, что и раньше, и с API voicechat-api 2.6.20,
 * ЗАНОВО сверенным по содержимому jar-а (не по памяти о старых сигнатурах — часть
 * могла измениться между версиями).
 *
 * Регистрируется через fabric.mod.json entrypoint "voicechat" (ключ, по которому SVC
 * ищет реализации {@link de.maxhenkel.voicechat.api.VoicechatPlugin} у всех модов).
 *
 * Пайплайн: {@link MicrophonePacketEvent} (сервер получает уже расшифрованный по
 * сети Opus-пакет от конкретного игрока — событие СЕРВЕРНОЕ, проверено разбором jar-а:
 * {@code PacketEvent extends ServerEvent}) → Opus decode → PCM 48 кГц →
 * {@link UtteranceDetector} (сегментация по паузам в потоке пакетов) → когда реплика
 * готова, в фоновом потоке: {@link PcmResampler} 48→16 кГц → {@link GigaAmClient}
 * (локальный gigaam_server.py, порт 5002) → если текст не пуст, считаем это
 * обращением к компаньону ЭТОГО игрока (проверка имени ВРЕМЕННО отключена, см.
 * {@link #mentionsCompanionName}) → {@link #handleAddressed}: {@link BrainRequestPayload}
 * клиенту того же игрока и асинхронное ожидание {@link BrainReplyPayload} (раздел 11
 * ТЗ, шаг 5 — LLM-инференс клиентский, раздел 6 ТЗ, эхо-заглушка заменена настоящим
 * ответом brain, с таймаутом и вежливым фолбэком, если клиент не ответил) →
 * {@link PiperClient} (TTS) → непозиционный аудио-канал SVC, нацеленный только на
 * подключение этого игрока ({@link StaticAudioChannel} — 15.09.2026, см. javadoc
 * {@link #speakReply}: раньше был {@code LocationalAudioChannel}, привязанный к точке
 * в мире и потому затухающий по расстоянию, что противоречило идее приватного
 * компаньона "в голове у игрока"). HUD-состояния
 * синхронизируются на клиент того же игрока через {@link SetCompanionHudStatePayload}
 * (раздел 11 ТЗ) на каждом шаге — THINKING теперь покрывает не только STT, но и
 * весь round-trip до ответа brain.
 */
public class NagumoVoicechatPlugin implements VoicechatPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");

    /** Как часто планировщик проверяет «зависшие» реплики (пакеты перестали идти). */
    private static final long FLUSH_INTERVAL_MS = 250;

    /** Формат кадра SVC (48 кГц) — во столько же ресемплируем ответ Piper перед отправкой. */
    private static final int OUTPUT_SAMPLE_RATE = 48_000;

    /**
     * Сколько ждать ответа клиентского {@code brain} (раздел 6 ТЗ), прежде чем считать
     * его недоступным. Модель уровня standard может думать долго на слабом железе —
     * взято верхней границей из указанного в задаче диапазона "секунд 20-30".
     */
    private static final long BRAIN_TIMEOUT_SECONDS = 25;

    /** Вежливое сообщение, если клиентский brain не ответил (таймаут/Ollama не запущена и т.п.). */
    private static final String BRAIN_UNAVAILABLE_MESSAGE =
            "Прости, я сейчас задумался и не успел ответить — попробуй повторить чуть позже.";

    /**
     * Ссылка на себя для кода вне графа зависимостей SVC (по тому же паттерну, что и
     * {@code VerityServer.companions()}) — SVC создаёт этот плагин сам через entrypoint
     * "voicechat", передать ссылку через конструктор некому. Используется
     * {@link ru.nagumo.verity.server.command.NagumoCommands#register}, чтобы
     * {@code /nagumo ask} мог прогнать текст через тот же {@link #handleAddressed}, что
     * и голос — удобно для проверки brain/{@code GameKnowledgeIndex} без микрофона.
     */
    private static volatile NagumoVoicechatPlugin instance;

    public static NagumoVoicechatPlugin instance() {
        return instance;
    }

    private VoicechatServerApi api;

    /**
     * По одному Opus-декодеру на говорящего игрока, живёт пока игрок на сервере.
     * OpusDecoder — stateful (держит межфреймовый контекст Opus между вызовами
     * {@code decode()}). Создавать/закрывать его на каждый 20-мс пакет — терять
     * контекст восстановления сигнала (шум на реальном микрофоне). Не закрыть при
     * выходе игрока — течь нативной памяти (сказано в доке SVC), поэтому чистим в
     * PlayerDisconnectedEvent.
     */
    private final Map<UUID, OpusDecoder> opusDecoders = new ConcurrentHashMap<>();

    /** По одному детектору реплик на говорящего игрока. */
    private final Map<UUID, UtteranceDetector> detectors = new ConcurrentHashMap<>();

    /** STT-движок — GigaAM (русская речь), см. {@link GigaAmClient}. */
    private final GigaAmClient stt = new GigaAmClient();

    /** TTS-движок — Piper, см. {@link PiperClient}. */
    private final PiperClient piper = new PiperClient();

    /**
     * Пул под блокирующие вызовы STT-сервера — обработчик MicrophonePacketEvent
     * (поток голосовых пакетов SVC) блокировать нельзя.
     */
    private final ExecutorService sttExecutor =
            Executors.newCachedThreadPool(daemonFactory("nagumo-stt"));

    /** Отдельный пул под TTS (piper) — чтобы всплеск озвучки одного игрока не голодал STT других. */
    private final ExecutorService ttsExecutor =
            Executors.newCachedThreadPool(daemonFactory("nagumo-tts"));

    /** Планировщик, добивающий последнюю реплику, после которой пакеты просто кончились. */
    private final ScheduledExecutorService flusher =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("nagumo-vad-flush"));

    /**
     * Ответы клиентского {@code brain} на {@link BrainRequestPayload}, ожидаемые прямо
     * сейчас — по одному {@link CompletableFuture} на {@code requestId}. Завершается
     * либо приёмом {@link BrainReplyPayload} ({@link #onBrainReply}), либо таймаутом
     * ({@link #BRAIN_TIMEOUT_SECONDS}). requestId сквозной на весь плагин (не по
     * игроку) — пайплайн и так последовательный на игрока, но общий счётчик проще
     * и не мешает логировать по requestId однозначно при нескольких игроках сразу.
     */
    private final Map<Long, CompletableFuture<String>> pendingBrainRequests = new ConcurrentHashMap<>();
    private final AtomicLong brainRequestCounter = new AtomicLong();

    /** true, пока клиентский brain не отвечает (таймаут/не запущена Ollama) — не спамить лог на каждую реплику. */
    private final AtomicBoolean brainWarnedDown = new AtomicBoolean(false);

    @Override
    public String getPluginId() {
        return "nagumo";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (!(api instanceof VoicechatServerApi serverApi)) {
            LOGGER.warn("[NAGUMO-VOICE] VoicechatApi не серверный — голосовой пайплайн недоступен");
            return;
        }
        this.api = serverApi;
        instance = this;

        // Гарантия порядка (15.09.2026, см. javadoc NagumoPayloads.registerCommon()):
        // Fabric не гарантирует, что VerityServer.onInitialize() (обычный entrypoint
        // мода nagumo) выполнится раньше, чем voicechat синхронно вызовет этот метод
        // изнутри СВОЕГО onInitialize() — на практике voicechat инициализируется
        // первым. Без этого вызова здесь регистрация приёмника BrainReplyPayload ниже
        // падала с IllegalArgumentException ("тип пакета ещё не зарегистрирован"),
        // молча проглатываемым PluginManager'ом SVC — ответ клиентского brain никогда
        // не долетал, отсюда постоянный таймаут. registerCommon() идемпотентна, так
        // что повторный вызов из VerityServer (в любом порядке относительно этого) безопасен.
        NagumoPayloads.registerCommon();

        flusher.scheduleWithFixedDelay(this::flushStaleUtterances,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        // Ответ клиентского brain на реплику, отправленную из handleAddressed ниже.
        ServerPlayNetworking.registerGlobalReceiver(BrainReplyPayload.ID, (payload, context) -> onBrainReply(payload));
        LOGGER.info("[NAGUMO-VOICE] плагин Simple Voice Chat инициализирован");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicPacket);
        registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
            UUID uuid = event.getPlayerUuid();
            UtteranceDetector removed = detectors.remove(uuid);
            if (removed != null && removed.isActive()) {
                LOGGER.info("[NAGUMO-VAD] игрок {} отключился с незавершённой репликой — отброшено", uuid);
            }
            OpusDecoder decoder = opusDecoders.remove(uuid);
            if (decoder != null) {
                decoder.close(); // иначе — течь нативной памяти
            }
        });
    }

    private void onMicPacket(MicrophonePacketEvent event) {
        try {
            VoicechatConnection sender = event.getSenderConnection();
            if (sender == null) {
                return;
            }
            ServerPlayer speakerPlayer = sender.getPlayer();
            if (speakerPlayer == null) {
                return;
            }
            UUID speaker = speakerPlayer.getUuid();

            if (!(event.getPacket() instanceof MicrophonePacket micPacket)) {
                return;
            }

            // Один декодер на игрока, переиспользуется на всю серию пакетов (stateful).
            // createDecoder() — @Nullable: null, если для платформы нет нативной libopus;
            // ConcurrentHashMap.computeIfAbsent тогда ничего не сохраняет и вернёт null.
            // SVC доставляет пакеты одного игрока последовательно одним потоком, так что
            // decode() на этом декодере не конкурирует сам с собой.
            OpusDecoder decoder = opusDecoders.computeIfAbsent(speaker, k -> api.createDecoder());
            if (decoder == null) {
                LOGGER.warn("[NAGUMO-VOICE] нет нативного OpusDecoder на этой платформе — пакет пропущен");
                return;
            }

            short[] pcm = decoder.decode(micPacket.getOpusEncodedData());
            if (pcm == null || pcm.length == 0) {
                return;
            }

            UtteranceDetector.Utterance utterance = detectors.computeIfAbsent(speaker, k -> new UtteranceDetector())
                    .feed(pcm, System.currentTimeMillis());
            if (utterance != null) {
                processUtterance(speaker, utterance);
            }
        } catch (Throwable t) {
            // Поток голосовых пакетов SVC — если тут вылетит, SVC может выключить приём. Гасим и логируем.
            LOGGER.error("[NAGUMO-VOICE] сбой обработки пакета с микрофона: {}", t.getMessage(), t);
        }
    }

    /** Периодически: закрыть реплику, после которой пакеты перестали приходить. */
    private void flushStaleUtterances() {
        // scheduleWithFixedDelay молча ОСТАНАВЛИВАЕТ задачу при исключении — ловим Throwable.
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, UtteranceDetector> entry : detectors.entrySet()) {
                UtteranceDetector.Utterance utterance = entry.getValue().checkTimeout(now);
                if (utterance != null) {
                    processUtterance(entry.getKey(), utterance);
                }
            }
        } catch (Throwable t) {
            LOGGER.error("[NAGUMO-VAD] сбой планировщика пауз: {}", t.getMessage(), t);
        }
    }

    private void processUtterance(UUID speaker, UtteranceDetector.Utterance utterance) {
        sttExecutor.execute(() -> {
            try {
                short[] forStt = utterance.forWhisper();
                if (forStt == null) {
                    return; // UtteranceDetector уже залогировал причину отброса (тишина/коротко)
                }
                short[] pcm16k = PcmResampler.resample(forStt, 48_000, 16_000);
                String text = stt.transcribe(pcm16k);
                if (text == null || text.isBlank()) {
                    LOGGER.info("[NAGUMO-STT] от={} — пусто (gigaam-server недоступен либо вернул пустую строку)", speaker);
                    return;
                }
                LOGGER.info("[NAGUMO-STT] от={} текст=\"{}\"", speaker, text);

                // TODO: временно отключено 10.09.2026 по просьбе пользователя — включить
                // обратно, когда точность распознавания речи будет протестирована и
                // достаточна. Иначе ИИ отвечает на любой звук/бормотание/разговор игрока
                // с другими игроками. См. mentionsCompanionName ниже — метод оставлен
                // нетронутым и невызываемым, а не удалён.
                // CompanionData nameCheck = VerityServer.companions().getOrCreate(speaker);
                // if (!mentionsCompanionName(text, nameCheck.getName())) {
                //     return;
                // }

                handleAddressed(speaker, text.trim());
            } catch (Throwable t) {
                LOGGER.error("[NAGUMO-STT] ошибка обработки реплики от {}: {}", speaker, t.getMessage(), t);
            }
        });
    }

    /**
     * Раздел 11 ТЗ, шаг 5: любой непустой распознанный текст сейчас считается
     * обращением к компаньону ЭТОГО игрока (проверка имени отключена, см.
     * {@link #mentionsCompanionName} и TODO в {@link #processUtterance}). Эхо-заглушка
     * заменена на реальный запрос к клиентскому {@code brain} (раздел 6 ТЗ): сервер
     * отправляет {@link BrainRequestPayload} и асинхронно ждёт {@link BrainReplyPayload}
     * с тем же {@code requestId} — LLM-инференс намеренно клиентский (экономия GPU),
     * поэтому ответ приходит по сети, а не вычисляется тут же.
     *
     * Ответ не получает префикса с именем компаньона (как было у эхо-заглушки и до сих
     * пор есть у {@code /nagumo ask}) — системный промпт клиентского brain уже
     * формируется от имени компаньона и в его характере (раздел 8 ТЗ), добавлять имя
     * ещё раз здесь было бы дублированием стиля стаба, а не осознанным решением.
     */
    /**
     * Публичный вход в тот же пайплайн для {@code /nagumo ask} (см. {@link #instance()}) —
     * текст обрабатывается ИДЕНТИЧНО распознанной голосовой реплике: HUD-состояния,
     * запрос {@link BrainRequestPayload}/ожидание {@link BrainReplyPayload}, TTS-озвучка
     * через Piper. Полезно для проверки brain/{@code GameKnowledgeIndex} без микрофона.
     */
    public void askBrain(UUID speaker, String text) {
        handleAddressed(speaker, text);
    }

    /**
     * Публичный вход для {@code /nagumo speak} (обсуждение 15.09.2026, A/B-тест
     * Silero против Piper, см. ТЗ) — озвучивает {@code text} НАПРЯМУЮ, минуя
     * brain целиком: для теста TTS-движка важно слышать именно заданную фразу без
     * риска, что LLM её перефразирует. Не идёт через {@link #handleAddressed} —
     * только {@link #speakReply} (TTS + аудиоканал) и показ текста, HUD-состояния
     * THINKING/LISTENING не нужны (brain не вызывается).
     */
    public void speakText(UUID speaker, String text) {
        sendShowText(speaker, text);
        ttsExecutor.execute(() -> speakReply(speaker, text));
    }

    private void handleAddressed(UUID speaker, String text) {
        sendHudState(speaker, "LISTENING");
        sendHudState(speaker, "THINKING"); // покрывает и сетевой round-trip, и сам инференс — до самого ответа

        long requestId = brainRequestCounter.incrementAndGet();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingBrainRequests.put(requestId, future);

        net.minecraft.server.level.ServerPlayer mcPlayer = resolveMcPlayer(speaker);
        if (mcPlayer == null) {
            pendingBrainRequests.remove(requestId);
            LOGGER.warn("[NAGUMO-BRAIN] игрок {} уже не в сети — запрос \"{}\" к brain отменён", speaker, text);
            sendHudState(speaker, "IDLE");
            return;
        }
        ServerPlayNetworking.send(mcPlayer, new BrainRequestPayload(requestId, text));

        future.orTimeout(BRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete((reply, error) -> {
            pendingBrainRequests.remove(requestId);
            String finalReply;
            if (error != null) {
                warnBrainOnce("клиент " + speaker + " не ответил за " + BRAIN_TIMEOUT_SECONDS
                        + "с (таймаут) или отключился: " + error);
                finalReply = BRAIN_UNAVAILABLE_MESSAGE;
            } else if (reply == null || reply.isBlank()) {
                warnBrainOnce("клиент " + speaker + " прислал пустой ответ brain (см. лог клиента — вероятно, Ollama недоступна)");
                finalReply = BRAIN_UNAVAILABLE_MESSAGE;
            } else {
                brainWarnedDown.set(false); // brain снова отвечает
                finalReply = reply;
            }

            sendShowText(speaker, finalReply);
            ttsExecutor.execute(() -> speakReply(speaker, finalReply));
        });
    }

    /** Завершает future, ожидающий ответа на {@code requestId} из {@link #handleAddressed}. */
    private void onBrainReply(BrainReplyPayload payload) {
        CompletableFuture<String> future = pendingBrainRequests.remove(payload.requestId());
        if (future == null) {
            // Позднее пришедший ответ на уже протухший (по таймауту) requestId — не ошибка, просто лог.
            LOGGER.warn("[NAGUMO-BRAIN] ответ на неизвестный/устаревший requestId={} проигнорирован", payload.requestId());
            return;
        }
        future.complete(payload.replyText());
    }

    private void warnBrainOnce(String message) {
        if (brainWarnedDown.compareAndSet(false, true)) {
            LOGGER.warn("[NAGUMO-BRAIN] {}", message);
        }
    }

    /**
     * Синтезировать {@code reply} и проиграть его НЕпозиционно, лично владельцу
     * компаньона (правка 15.09.2026, ТЗ: "звучит как будто в голове у игрока",
     * без затухания по расстоянию и без слышимости для посторонних).
     *
     * Раньше здесь использовался {@code LocationalAudioChannel} (раздел 5 ТЗ старой
     * версии — "позиционно", "в будущем — рядом стоящим тоже") — звук был точкой в
     * мире на позиции игрока НА МОМЕНТ ответа и затухал/пропадал, если игрок отходил.
     * Это противоречило задуманному поведению приватного HUD-компаньона (раздел 7 ТЗ:
     * не физическая сущность, всегда "рядом" в смысле интерфейса).
     *
     * {@link StaticAudioChannel} (voicechat-api 2.6.20, проверено разбором jar-а —
     * {@code de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel}) — то, что
     * нужно: не позиционный ({@code AudioChannel} без {@code getLocation()}/
     * {@code setDistance()}), с явным списком целей ({@code addTarget}/
     * {@code removeTarget}/{@code clearTargets}). Создаём через
     * {@code VoicechatServerApi.createStaticAudioChannel(UUID)} — есть и удобный
     * трёхаргументный оверлоад {@code (UUID, ServerLevel, VoicechatConnection)},
     * сразу добавляющий цель, но в этой версии API (2.6.20) он помечен
     * {@code @Deprecated} (проверено рефлексией по jar-у, не по памяти) в пользу
     * именно этой пары "создать + {@code addTarget}" — используем непомеченный путь.
     * Единственная цель — {@code connection} того же игрока, что и раньше резолвился
     * для позиции: звук идёт только владельцу этого компаньона, никому больше вокруг.
     */
    private void speakReply(UUID speaker, String reply) {
        try {
            long t0 = System.nanoTime();
            WavUtil.WavData wav = piper.synthesize(reply);
            if (wav == null) {
                LOGGER.warn("[NAGUMO-TTS] piper не дал аудио для: \"{}\"", reply);
                sendHudState(speaker, "IDLE");
                return;
            }
            short[] pcm48k = PcmResampler.resample(wav.pcm(), wav.sampleRate(), OUTPUT_SAMPLE_RATE);
            long synthMs = (System.nanoTime() - t0) / 1_000_000;

            VoicechatConnection connection = api.getConnectionOf(speaker);
            ServerPlayer svcPlayer = connection != null ? connection.getPlayer() : null;
            if (svcPlayer == null) {
                LOGGER.warn("[NAGUMO-TTS] игрок {} уже не в сети — озвучка \"{}\" отменена", speaker, reply);
                sendHudState(speaker, "IDLE");
                return;
            }

            StaticAudioChannel channel = api.createStaticAudioChannel(UUID.randomUUID());
            if (channel == null) {
                LOGGER.warn("[NAGUMO-TTS] SVC не создал аудиоканал для реплики \"{}\"", reply);
                sendHudState(speaker, "IDLE");
                return;
            }
            channel.addTarget(connection);

            OpusEncoder encoder = api.createEncoder();
            AudioPlayer player = api.createAudioPlayer(channel, encoder, pcm48k);

            sendHudState(speaker, "SPEAKING");
            // нарезку на 960-сэмпловые кадры и таймирование SVC делает сам;
            // энкодер держит нативную память — закрываем по завершении проигрывания.
            player.setOnStopped(() -> {
                encoder.close();
                sendHudState(speaker, "IDLE");
            });
            player.startPlaying();

            LOGGER.info("[NAGUMO-TTS] играю \"{}\" для {} — {} сэмплов 48кГц (~{} мс аудио), синтез {} мс",
                    reply, speaker, pcm48k.length, pcm48k.length * 1000L / OUTPUT_SAMPLE_RATE, synthMs);
        } catch (Throwable e) {
            LOGGER.error("[NAGUMO-TTS] сбой озвучки \"{}\" для {}: {}", reply, speaker, e.getMessage(), e);
            sendHudState(speaker, "IDLE");
        }
    }

    /**
     * Имя встречается в тексте как отдельное слово (границы слова, без учёта регистра,
     * с поддержкой кириллицы) — та же логика, что была в старом
     * {@code CompanionAddressing.mentionsName} / клиентском {@code VoicePipeline.mentionsName}
     * (archive/pre-client-pivot-2026-09-10, archive/server-side-voice-revert-2026-09-10),
     * но сейчас НЕ вызывается (см. TODO в {@link #processUtterance}) — оставлен как
     * готовый к включению приватный метод, а не удалён.
     */
    @SuppressWarnings("unused")
    private static boolean mentionsCompanionName(String text, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(name) + "\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
        return pattern.matcher(text).find();
    }

    private void sendShowText(UUID playerId, String text) {
        withMcPlayer(playerId, mcPlayer ->
                ServerPlayNetworking.send(mcPlayer, new ShowTextPayload(mcPlayer.getId(), text)));
    }

    private void sendHudState(UUID playerId, String state) {
        withMcPlayer(playerId, mcPlayer ->
                ServerPlayNetworking.send(mcPlayer, new SetCompanionHudStatePayload(state)));
    }

    /**
     * SVC оборачивает игрока в свой собственный {@code de.maxhenkel.voicechat.api.ServerPlayer}
     * — сетевые пакеты этого мода нужно слать через НАСТОЯЩИЙ
     * {@code net.minecraft.server.level.ServerPlayer}. {@code Player.getPlayer()}
     * возвращает исходный платформенный объект как {@code Object} — самый прямой путь
     * получить его обратно, без плетения отдельной ссылки на {@code MinecraftServer}
     * через {@code VerityServer}. Используется и напрямую ({@link #handleAddressed}
     * для {@link BrainRequestPayload}, где нужен именно {@code ServerPlayer}, а не
     * просто "сделать что-то с ним"), и через {@link #withMcPlayer} для остальных
     * fire-and-forget отправок ({@link #sendShowText}/{@link #sendHudState}).
     */
    private net.minecraft.server.level.ServerPlayer resolveMcPlayer(UUID playerId) {
        VoicechatConnection connection = api.getConnectionOf(playerId);
        if (connection == null) {
            return null; // игрок уже не в сети
        }
        ServerPlayer svcPlayer = connection.getPlayer();
        if (svcPlayer == null) {
            return null;
        }
        if (svcPlayer.getPlayer() instanceof net.minecraft.server.level.ServerPlayer mcPlayer) {
            return mcPlayer;
        }
        LOGGER.warn("[NAGUMO-VOICE] неожиданный тип платформенного игрока у SVC для {}", playerId);
        return null;
    }

    private void withMcPlayer(UUID playerId, Consumer<net.minecraft.server.level.ServerPlayer> action) {
        net.minecraft.server.level.ServerPlayer mcPlayer = resolveMcPlayer(playerId);
        if (mcPlayer != null) {
            action.accept(mcPlayer);
        }
        // mcPlayer == null: игрок офлайн либо неожиданный тип — resolveMcPlayer уже залогировал причину.
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    LOGGER.error("[NAGUMO] необработанное исключение в потоке {}: {}",
                            thread.getName(), ex.getMessage(), ex));
            return t;
        };
    }
}
