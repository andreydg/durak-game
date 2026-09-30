package com.example.durakgame.service.autoplay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** One {@code generateContent} POST. Abstracted so tests can script responses and failures. */
@FunctionalInterface
interface GeminiTransport {
    HttpResult post(URI endpoint, String apiKey, String jsonBody, Duration timeout)
            throws IOException, InterruptedException;

    record HttpResult(int statusCode, String body) {
        boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    /** JDK client; the API key travels only in the {@code x-goog-api-key} header, never in the URL. */
    static GeminiTransport jdk(Duration connectTimeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        return (endpoint, apiKey, jsonBody, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("x-goog-api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.body() == null ? "" : response.body());
        };
    }
}
