package ru.nagumo.verity.server.voice;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сегментация речи на реплики по паузам в потоке голосовых пакетов SVC (этап 3,
 * раздел 5 ТЗ). Свой инстанс на каждого говорящего игрока.
 *
 * Идея: SVC присылает {@code MicrophonePacketEvent} только пока игрок реально
 * передаёт голос (зажата кнопка / сработала голосовая активация). Как только он
 * замолчал / отпустил кнопку — пакеты просто перестают идти. Поэтому границу
 * реплики надёжнее ловить по паузе между приходами пакетов, чем по RMS внутри
 * и без того «речевого» потока (RMS-порог молча терял тихие реплики — из-за
 * этого пропадала каждая третья фраза без единой строки в логе).
 *
 * Готовая реплика отдаётся как {@link Utterance}: {@code raw} — сырой 48 кГц буфер
 * до обрезки (нужен для диагностического дампа ВСЕГДА), {@code forWhisper} — то же
 * после {@link SilenceTrimmer} (или {@code null}, если реплика отброшена как тишина
 * / слишком короткая). Сегментацию (границы по паузам в потоке пакетов) обрезка
 * тишины НЕ трогает.
 *
 * Потокобезопасен: {@link #feed} зовётся из потока голосовых пакетов SVC,
 * {@link #checkTimeout} — из отдельного планировщика (см. NagumoVoicechatPlugin).
 */
public final class UtteranceDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");

    /** Частота PCM на входе (для перевода числа сэмплов в мс). */
    private static final int INPUT_SAMPLE_RATE = 48_000;

    /** Пауза в потоке пакетов без нового пакета (мс) = конец текущей реплики. */
    private static final long SILENCE_GAP_MS = 400;

    /** Реплики короче этого — отбрасываем (случайное нажатие, щелчок), но ЛОГИРУЕМ. */
    private static final long MIN_UTTERANCE_MS = 300;

    private final List<short[]> frames = new ArrayList<>();
    private boolean active = false;
    private long lastPacketMs;

    /**
     * Готовая реплика.
     *
     * @param raw        сырой 48 кГц PCM реплики ДО обрезки тишины (всегда непустой)
     * @param forWhisper тот же буфер после {@link SilenceTrimmer}, либо {@code null},
     *                   если реплика отброшена (сплошная тишина / короче минимума)
     */
    public record Utterance(short[] raw, short[] forWhisper) {
    }

    /**
     * Скормить один пакет с микрофона.
     *
     * @param frame PCM 48 кГц, 16-бит, моно (~960 сэмплов / 20 мс).
     * @param nowMs момент прихода пакета ({@code System.currentTimeMillis()}).
     * @return {@link Utterance}, если этот пакет пришёл после паузы и тем самым
     *         закрыл предыдущую реплику (новая при этом начинает накапливаться);
     *         иначе {@code null}.
     */
    public synchronized Utterance feed(short[] frame, long nowMs) {
        Utterance finished = null;
        if (active && nowMs - lastPacketMs > SILENCE_GAP_MS) {
            finished = finish(); // между пакетами была пауза — прошлая реплика уже закончилась
        }
        if (!active) {
            startNew(frame, nowMs);
        } else {
            addFrame(frame, nowMs);
        }
        return finished;
    }

    /**
     * Вызывать периодически из планировщика: закрывает «зависшую» реплику, если
     * пакеты просто перестали приходить (последняя фраза, после которой игрок
     * замолчал — иначе она висела бы до следующей его реплики).
     *
     * @return {@link Utterance} или {@code null}.
     */
    public synchronized Utterance checkTimeout(long nowMs) {
        if (active && nowMs - lastPacketMs > SILENCE_GAP_MS) {
            return finish();
        }
        return null;
    }

    /** Есть ли сейчас незавершённая накапливаемая реплика (для диагностики). */
    public synchronized boolean isActive() {
        return active;
    }

    private void startNew(short[] frame, long nowMs) {
        active = true;
        frames.clear();
        addFrame(frame, nowMs);
        LOGGER.debug("[NAGUMO-VAD] начало реплики (t={})", nowMs);
    }

    private void addFrame(short[] frame, long nowMs) {
        frames.add(frame);
        lastPacketMs = nowMs;
    }

    /**
     * Завершить накапливаемую реплику: собрать сырой PCM, прогнать через
     * {@link SilenceTrimmer}, залогировать итог. Возвращает {@link Utterance} с
     * сырым буфером всегда — решение об отбросе влияет только на {@code forWhisper}.
     */
    private Utterance finish() {
        short[] raw = concat(frames);
        long fullMs = Math.round(raw.length * 1000.0 / INPUT_SAMPLE_RATE);
        reset();

        SilenceTrimmer.Result t = SilenceTrimmer.trim(raw);

        if (t.audio() == null) {
            LOGGER.debug("[NAGUMO-VAD] реплика отброшена (сплошная тишина): длительность={}мс peak_rms={} порог={}",
                    fullMs, Math.round(t.peakRms()), Math.round(SilenceTrimmer.SPEECH_RMS_THRESHOLD));
            return new Utterance(raw, null);
        }

        long ms = Math.round(t.audio().length * 1000.0 / INPUT_SAMPLE_RATE);
        if (ms < MIN_UTTERANCE_MS) {
            LOGGER.debug("[NAGUMO-VAD] реплика отброшена (короче {}мс): длительность={}мс (до обрезки: {}мс) peak_rms={}",
                    MIN_UTTERANCE_MS, ms, fullMs, Math.round(t.peakRms()));
            return new Utterance(raw, null);
        }

        LOGGER.debug("[NAGUMO-VAD] длительность={} (до обрезки: {}) rms_min={} rms_avg={} rms_max={} peak_rms={}",
                ms, fullMs,
                Math.round(t.rmsMin()), Math.round(t.rmsAvg()), Math.round(t.rmsMax()), Math.round(t.peakRms()));
        return new Utterance(raw, t.audio());
    }

    private void reset() {
        frames.clear();
        active = false;
    }

    private static short[] concat(List<short[]> frames) {
        int total = 0;
        for (short[] f : frames) {
            total += f.length;
        }
        short[] out = new short[total];
        int pos = 0;
        for (short[] f : frames) {
            System.arraycopy(f, 0, out, pos, f.length);
            pos += f.length;
        }
        return out;
    }
}
