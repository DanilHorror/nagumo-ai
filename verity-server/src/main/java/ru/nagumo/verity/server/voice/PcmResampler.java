package ru.nagumo.verity.server.voice;

/**
 * Ресемплинг 16-битного моно PCM между произвольными частотами (этап 3, раздел 5 ТЗ):
 * STT — 48 кГц → 16 кГц (вниз), TTS — 22.05 кГц → 48 кГц (вверх).
 *
 * Одна windowed-sinc интерполяция (окно Хэмминга) в обе стороны:
 * out[n] = Σ_k in[k] · h(x_n − k), где x_n = n · fromRate/toRate — дробная позиция
 * выходного отсчёта во входной шкале, h — оконный sinc.
 * Срез фильтра — под найквистом МЕНЬШЕЙ из двух частот (с запасом), поэтому и при
 * децимации нет алиасинга, и при апсемплинге нет зеркальных образов.
 * Нормировка на сумму весов на каждом отсчёте — единичное усиление независимо
 * от дробного сдвига и на краях буфера.
 */
public final class PcmResampler {

    /** Доля найквиста меньшей частоты, на которой ставится срез (0.90 → небольшой guard-band). */
    private static final double CUTOFF_FRACTION = 0.90;

    /** Сколько нулей sinc ловить с каждой стороны — компромисс качество/скорость. */
    private static final double LOBES = 16.0;

    private PcmResampler() {
    }

    public static short[] resample(short[] pcm, int fromRate, int toRate) {
        if (fromRate <= 0 || toRate <= 0) {
            throw new IllegalArgumentException("частоты должны быть > 0");
        }
        if (fromRate == toRate || pcm.length == 0) {
            return pcm.clone();
        }

        double step = (double) fromRate / toRate;            // входных отсчётов на один выходной
        int outLen = (int) Math.floor(pcm.length / step);

        // нормированная (циклов на входной отсчёт) частота среза: половина найквиста
        // меньшей из частот, с запасом
        double fc = 0.5 * Math.min(1.0, (double) toRate / fromRate) * CUTOFF_FRACTION;

        // полуширина ядра во входных отсчётах — чтобы захватить ~LOBES нулей sinc
        int half = (int) Math.ceil(LOBES / (2.0 * fc));
        double windowSpan = 2.0 * half;

        short[] out = new short[outLen];
        for (int n = 0; n < outLen; n++) {
            double center = n * step;
            int k0 = (int) Math.floor(center) - half;
            int k1 = (int) Math.floor(center) + half + 1;

            double acc = 0.0;
            double wsum = 0.0;
            for (int k = k0; k <= k1; k++) {
                if (k < 0 || k >= pcm.length) {
                    continue;
                }
                double dt = center - k;                       // расстояние во входных отсчётах
                double window = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * (dt + half) / windowSpan); // Хэмминг
                double coeff = sinc(2.0 * fc * dt) * (2.0 * fc) * window;
                acc += pcm[k] * coeff;
                wsum += coeff;
            }

            double v = wsum != 0.0 ? acc / wsum : 0.0;
            out[n] = clamp(Math.round(v));
        }
        return out;
    }

    private static double sinc(double x) {
        if (x == 0.0) {
            return 1.0;
        }
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }

    private static short clamp(long v) {
        if (v > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (v < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) v;
    }
}
