package ru.nagumo.verity.server.voice;

import com.google.gson.JsonObject;

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
 * Клиент локального piper-http-server (TTS), этап 3, раздел 5 ТЗ. Симметричен
 * {@link WhisperClient}: POST JSON → готовый WAV в ответе.
 *
 * piper-http-server запускается отдельно от Minecraft-сервера (не часть Gradle-сборки):
 * <pre>python -m piper.http_server -m ru_RU-irina-medium --host 127.0.0.1 --port 5001</pre>
 *
 * Контракт: {@code POST /synthesize}, {@code Content-Type: application/json},
 * тело {@code {"text": "..."}}. Ответ — {@code audio/wav}, целиком (не потоково),
 * внутри 22050 Гц / 16 бит / моно.
 *
 * Если сервер не поднят / упал — {@link #synthesize} логирует предупреждение ОДИН
 * раз и возвращает {@code null}, не роняя остальное.
 */
public final class PiperClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("nagumo");

    // TODO(debug, 15.09.2026): ВРЕМЕННО указывает на silero_server.py (:5003) для
    // живого A/B-теста Silero vs Piper (ТЗ, "ужасная" русская речь у Piper/
    // espeak-ng — известная проблема с ударениями). Piper на :5001 не трогается,
    // не выпиливается — откат: вернуть порт на 5001. Контракт (POST /synthesize,
    // {"text": "..."} -> audio/wav) у обоих серверов одинаковый, поэтому смена
    // порта — единственное необходимое изменение здесь.
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:5003/synthesize");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /** true, пока piper-server недоступен — чтобы не спамить варнингом в каждый вызов. */
    private final AtomicBoolean warnedDown = new AtomicBoolean(false);

    /**
     * Блокирующий сетевой вызов — НЕ вызывать из игрового потока / потока голосовых пакетов.
     *
     * @return синтезированный PCM (16 бит, моно) + его частота из WAV-заголовка (обычно
     *         22050), либо {@code null} при любой ошибке.
     */
    public WavUtil.WavData synthesize(String text) {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                warnOnce("piper-server ответил HTTP " + response.statusCode());
                return null;
            }
            warnedDown.set(false); // сервер снова жив
            return WavUtil.readPcm16(response.body());
        } catch (IOException e) {
            warnOnce("piper-server недоступен (" + e.getMessage() + ") — озвучка пропущена. "
                    + "Запусти: python -m piper.http_server -m ru_RU-irina-medium --host 127.0.0.1 --port 5001");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            warnOnce("ошибка разбора WAV от piper-server: " + e.getMessage());
            return null;
        }
    }

    private void warnOnce(String message) {
        if (warnedDown.compareAndSet(false, true)) {
            LOGGER.warn("[NAGUMO-TTS] " + message);
        }
    }
}
