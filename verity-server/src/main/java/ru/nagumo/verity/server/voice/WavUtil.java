package ru.nagumo.verity.server.voice;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * WAV (PCM, моно, 16 бит) ↔ {@code short[]}. {@link #toWav}/{@link #write} —
 * сборка (запрос к whisper-server, дампы реплик); {@link #readPcm16} —
 * разбор (ответ piper-http-server в {@link PiperClient}).
 */
public final class WavUtil {

    private WavUtil() {
    }

    /** Распарсенный WAV: 16-битный моно PCM + его частота дискретизации. */
    public record WavData(short[] pcm, int sampleRate) {
    }

    /**
     * Разобрать WAV-байты в 16-битный моно PCM. Проходит по чанкам RIFF (не
     * полагается на то, что {@code data} строго на offset 44 — piper кладёт чистый
     * заголовок, но между {@code fmt } и {@code data} может быть, например, {@code LIST}).
     * Многоканальный вход микшируется в моно; только 16-бит PCM.
     */
    public static WavData readPcm16(byte[] wav) {
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 12 || buf.getInt(0) != riff("RIFF") || buf.getInt(8) != riff("WAVE")) {
            throw new IllegalArgumentException("не WAV/RIFF (длина " + wav.length + ")");
        }

        int sampleRate = 0;
        int channels = 1;
        int bitsPerSample = 16;
        int pos = 12;
        while (pos + 8 <= wav.length) {
            int chunkId = buf.getInt(pos);
            int chunkSize = buf.getInt(pos + 4);
            int body = pos + 8;
            if (chunkId == riff("fmt ")) {
                channels = Short.toUnsignedInt(buf.getShort(body + 2));
                sampleRate = buf.getInt(body + 4);
                bitsPerSample = Short.toUnsignedInt(buf.getShort(body + 14));
            } else if (chunkId == riff("data")) {
                if (bitsPerSample != 16) {
                    throw new IllegalArgumentException("ожидался 16-бит PCM, а тут " + bitsPerSample);
                }
                int end = Math.min(body + chunkSize, wav.length);
                int frames = (end - body) / (2 * Math.max(1, channels));
                short[] pcm = new short[frames];
                int p = body;
                for (int i = 0; i < frames; i++) {
                    int acc = 0;
                    for (int c = 0; c < channels; c++) {
                        acc += buf.getShort(p);
                        p += 2;
                    }
                    pcm[i] = (short) (acc / channels);
                }
                return new WavData(pcm, sampleRate);
            }
            pos = body + chunkSize + (chunkSize & 1); // чанки выровнены по 2 байта
        }
        throw new IllegalArgumentException("в WAV нет чанка data");
    }

    private static int riff(String tag) {
        byte[] b = tag.getBytes(StandardCharsets.US_ASCII);
        return (b[0] & 0xFF) | (b[1] & 0xFF) << 8 | (b[2] & 0xFF) << 16 | (b[3] & 0xFF) << 24;
    }

    /** WAV-байты для PCM {@code pcm} с частотой {@code sampleRate} (моно, 16 бит). */
    public static byte[] toWav(short[] pcm, int sampleRate) {
        int dataSize = pcm.length * 2;
        int byteRate = sampleRate * 2; // channels(1) * bitsPerSample/8 (2)

        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + dataSize);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));

        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);              // Subchunk1Size (PCM)
        buf.putShort((short) 1);     // AudioFormat = PCM
        buf.putShort((short) 1);     // NumChannels = mono
        buf.putInt(sampleRate);      // SampleRate
        buf.putInt(byteRate);        // ByteRate
        buf.putShort((short) 2);     // BlockAlign
        buf.putShort((short) 16);    // BitsPerSample

        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataSize);
        for (short sample : pcm) {
            buf.putShort(sample);
        }
        return buf.array();
    }

    /** Записать WAV в файл, создав родительские каталоги. */
    public static void write(File file, short[] pcm, int sampleRate) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.write(file.toPath(), toWav(pcm, sampleRate));
    }
}
