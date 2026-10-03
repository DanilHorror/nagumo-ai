package ru.nagumo.verity.server.voice;

import java.util.Arrays;

/**
 * Обрезка тишины по краям реплики перед отправкой в whisper (этап 3, раздел 5 ТЗ).
 *
 * Зачем: SVC начинает слать пакеты по нажатию кнопки голоса и перестаёт по её
 * отпусканию, поэтому в начале реплики ~0.4–1.0 с почти полной тишины (пока человек
 * собрался сказать), и такой же тихий хвост. Whisper на этой тишине по краям
 * галлюцинирует — выдаёт случайную фразу вместо реальной речи в середине.
 *
 * Сегментацию реплики (начало/конец по паузам в потоке пакетов) это НЕ трогает —
 * только режет уже собранный PCM-буфер до фактической речи.
 *
 * Внутренние тихие места (мягкие согласные, паузы между словами) сохраняются:
 * берётся весь диапазон от первого до последнего «речевого» фрейма, а не только
 * фреймы выше порога.
 */
public final class SilenceTrimmer {

    private static final int FRAME_SAMPLES = 960;          // 20 мс при 48 кГц

    /** Порог RMS фрейма: выше — считаем речью. Подобран по реальным записям. */
    static final double SPEECH_RMS_THRESHOLD = 300.0;

    /**
     * Запас с каждой стороны обрезаемого диапазона. 10 фреймов ≈ 200 мс — 4 фрейма
     * (~83 мс) обрезали тихую атаку глухих смычных («т» в начале «тест»): у них
     * короткий тихий разгон громкости перед гласной длиннее 83 мс, из-за чего
     * первое слово смазывалось в распознавании.
     */
    private static final int MARGIN_FRAMES = 10;

    /**
     * ВРЕМЕННО (диагностика, {@code true}): не выбрасывать реплику как «сплошную
     * тишину» — даже если ни один фрейм не превысил порог, вернуть буфер целиком,
     * чтобы он ушёл в дамп/whisper и мы увидели, что там реально было.
     * Вернуть в {@code false} после диагностического прогона.
     */
    static final boolean DIAGNOSTIC_NEVER_DISCARD = false;

    private SilenceTrimmer() {
    }

    /**
     * @return {@link Result} с обрезанным аудио (либо {@code audio == null}, если
     *         реплика — сплошная тишина и {@link #DIAGNOSTIC_NEVER_DISCARD} выключен).
     *         {@code peakRms} заполнен всегда — для лога отброса.
     */
    public static Result trim(short[] pcm48k) {
        int frameCount = pcm48k.length / FRAME_SAMPLES;
        if (frameCount == 0) {
            return new Result(null, 0, 0, 0, 0);
        }

        double[] rms = new double[frameCount];
        double peak = 0.0;
        int first = -1;
        int last = -1;
        for (int f = 0; f < frameCount; f++) {
            rms[f] = frameRms(pcm48k, f * FRAME_SAMPLES);
            peak = Math.max(peak, rms[f]);
            if (rms[f] > SPEECH_RMS_THRESHOLD) {
                if (first < 0) {
                    first = f;
                }
                last = f;
            }
        }

        if (first < 0) {
            if (!DIAGNOSTIC_NEVER_DISCARD) {
                return new Result(null, peak, 0, 0, 0); // сплошная тишина
            }
            // диагностика: пропускаем как есть, без обрезки
            first = 0;
            last = frameCount - 1;
        }

        int fromFrame = Math.max(0, first - MARGIN_FRAMES);
        int toFrame = Math.min(frameCount - 1, last + MARGIN_FRAMES);
        int fromSample = fromFrame * FRAME_SAMPLES;
        int toSample = Math.min(pcm48k.length, (toFrame + 1) * FRAME_SAMPLES);
        short[] trimmed = Arrays.copyOfRange(pcm48k, fromSample, toSample);

        double min = Double.MAX_VALUE;
        double max = 0.0;
        double sum = 0.0;
        for (int f = fromFrame; f <= toFrame; f++) {
            min = Math.min(min, rms[f]);
            max = Math.max(max, rms[f]);
            sum += rms[f];
        }
        int frames = toFrame - fromFrame + 1;
        return new Result(trimmed, peak, min, sum / frames, max);
    }

    /**
     * @param audio   обрезанное аудио, либо {@code null} если отброшено как тишина
     * @param peakRms максимальный RMS фрейма по ВСЕЙ реплике (заполнен всегда)
     * @param rmsMin  RMS-статистика по обрезанному диапазону (0, если {@code audio == null})
     */
    public record Result(short[] audio, double peakRms, double rmsMin, double rmsAvg, double rmsMax) {
    }

    private static double frameRms(short[] pcm, int offset) {
        int n = Math.min(FRAME_SAMPLES, pcm.length - offset);
        if (n <= 0) {
            return 0.0;
        }
        long sumSquares = 0;
        for (int i = 0; i < n; i++) {
            short s = pcm[offset + i];
            sumSquares += (long) s * s;
        }
        return Math.sqrt((double) sumSquares / n);
    }
}
