package com.example.durakgame.service.autoplay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** One Messages API POST. Abstracted so tests can script responses and failures. */
@FunctionalInterface
interface AnthropicMessagesTransport {
    URI MESSAGES = URI.create("https://api.anthropic.com/v1/messages");
    String API_VERSION = "2023-06-01";

    /**
     * @throws AnthropicFederation.Unavailable when no access token could be had
     * @throws java.net.http.HttpTimeoutException when {@code timeout} ran out
     */
    HttpResult post(String jsonBody, Duration timeout) throws IOException, InterruptedException;

    /** The status, the body and Anthropic's {@code request-id} header (empty when absent). */
    record HttpResult(int statusCode, String body, String requestId) {
        boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    /**
     * Messages as the service's own identity, through {@link AnthropicFederation}. The timeout covers
     * all of it: waiting for an access token, the response headers and the complete body; a call whose
     * time runs out cancels its exchange. A 401 means Anthropic refused the token, so it is forgotten
     * and the next call exchanges again; the call itself is not retried.
     */
    static AnthropicMessagesTransport federated(AnthropicFederation federation, URI endpoint, HttpClient client,
                                                LongSupplier nanoTime) {
        return (jsonBody, timeout) -> {
            OutboundDeadline deadline = OutboundDeadline.after(timeout, nanoTime);
            String token = federation.accessToken(deadline);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(deadline.requestTimeout())
                    .header("content-type", "application/json")
                    .header("anthropic-version", API_VERSION)
                    .header("authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            CompletableFuture<HttpResponse<String>> pending = client.sendAsync(request,
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response = deadline.await(pending, OutboundDeadline.Abandon.CANCEL_EXCHANGE);
            if (response.statusCode() == 401) {
                federation.forget(token);
            }
            return new HttpResult(response.statusCode(), response.body() == null ? "" : response.body(),
                    response.headers().firstValue("request-id").orElse(""));
        };
    }
}
