package ru.nagumo.verity.server.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент локального whisper-server (whisper.cpp, {@code ./build/bin/whisper-server}),
 * этап 3, раздел 5 ТЗ. Собирает из PCM WAV в памяти и шлёт его multipart-POST'ом
 * на {@code /inference}.
 *
 * whisper-server запускается отдельно от Minecraft-сервера (не часть Gradle-сборки):
 * <pre>whisper-server -m models/ggml-base.bin -l ru --host 127.0.0.1 --port 8080</pre>
 *
 * Если сервер не поднят / упал — {@link #transcribe} логирует предупреждение ОДИН
 * раз и возвращает {@code null}, не роняя остальной плагин.
 */
public final class WhisperClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");

    private static final URI ENDPOINT = URI.create("http://127.0.0.1:8080/inference");
    private static final int SAMPLE_RATE = 16_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final String boundary = "NagumoWhisper" + Long.toHexString(System.nanoTime());

    /** true, пока whisper-server недоступен — чтобы не спамить варнингом в каждый вызов. */
    private final AtomicBoolean warnedDown = new AtomicBoolean(false);

    /**
     * Блокирующий сетевой вызов — НЕ вызывать из потока обработки голосовых пакетов SVC.
     *
     * @param pcm16k PCM 16 кГц, 16-бит, моно.
     * @return распознанный текст (обрезанный по краям) или {@code null} при любой ошибке.
     */
    public String transcribe(short[] pcm16k) {
        byte[] body = buildMultipartBody(WavUtil.toWav(pcm16k, SAMPLE_RATE));
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                warnOnce("whisper-server ответил HTTP " + response.statusCode());
                return null;
            }
            warnedDown.set(false); // сервер снова жив
            return extractText(response.body());
        } catch (IOException e) {
            warnOnce("whisper-server недоступен (" + e.getMessage() + ") — транскрипция пропущена. "
                    + "Запусти: whisper-server -m models/ggml-base.bin -l ru --host 127.0.0.1 --port 8080");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            warnOnce("ошибка разбора ответа whisper-server: " + e.getMessage());
            return null;
        }
    }

    private void warnOnce(String message) {
        if (warnedDown.compareAndSet(false, true)) {
            LOGGER.warn("[NAGUMO-STT] " + message);
        }
    }

    private static String extractText(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        if (!obj.has("text")) {
            return null;
        }
        String text = obj.get("text").getAsString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Тело multipart/form-data вручную (в java.net.http нет билдера): часть {@code file}
     * с WAV и часть {@code response_format=json}.
     */
    private byte[] buildMultipartBody(byte[] wav) {
        String pre = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"utterance.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";
        String mid = "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n"
                + "json";
        String post = "\r\n--" + boundary + "--\r\n";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(pre.getBytes(StandardCharsets.UTF_8));
            out.write(wav);
            out.write(mid.getBytes(StandardCharsets.UTF_8));
            out.write(post.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e); // ByteArrayOutputStream не бросает
        }
        return out.toByteArray();
    }
}
