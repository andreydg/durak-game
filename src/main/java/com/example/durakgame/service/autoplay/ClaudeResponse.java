package com.example.durakgame.service.autoplay;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * The parts of a Messages API response the engine uses. The answer is read only from {@code text}
 * blocks: thinking blocks are reasoning (and empty unless a summary is asked for), never the answer.
 *
 * @param answer       the answer object, or {@code null} when the text blocks hold none
 * @param stopReason   {@code stop_reason}: {@code end_turn}, {@code max_tokens}, {@code refusal}, ...
 * @param stopCategory {@code stop_details.category} of a refusal
 * @param usage        token counts from {@code usage} (zero when absent)
 * @param errorType    {@code error.type} of an error response, such as {@code overloaded_error}
 * @param errorMessage {@code error.message} of an error response
 */
record ClaudeResponse(
        JsonNode answer,
        String stopReason,
        String stopCategory,
        Usage usage,
        String errorType,
        String errorMessage
) {
    /**
     * Token counts. {@code inputTokens} is only the part after the cache breakpoint; thinking is
     * billed and counted inside {@code outputTokens}.
     */
    record Usage(int inputTokens, int cacheReadTokens, int cacheWriteTokens, int outputTokens) {
        static final Usage NONE = new Usage(0, 0, 0, 0);

        /** The whole prompt, cached or not. */
        int promptTokens() {
            return inputTokens + cacheReadTokens + cacheWriteTokens;
        }
    }

    static final ClaudeResponse EMPTY = new ClaudeResponse(null, null, null, Usage.NONE, null, null);

    /** One unambiguous object: no repeated field, nothing after it. */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    static ClaudeResponse parse(String body) {
        JsonNode root = readObject(body);
        if (root == null) {
            return EMPTY;
        }
        JsonNode error = root.path("error");
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                text.append(block.path("text").asText());
            }
        }
        return new ClaudeResponse(readObject(text.toString()), text(root.path("stop_reason")),
                text(root.path("stop_details").path("category")), usage(root.path("usage")),
                text(error.path("type")), text(error.path("message")));
    }

    private static Usage usage(JsonNode usage) {
        if (!usage.isObject()) {
            return Usage.NONE;
        }
        return new Usage(
                tokens(usage, "input_tokens"),
                tokens(usage, "cache_read_input_tokens"),
                tokens(usage, "cache_creation_input_tokens"),
                tokens(usage, "output_tokens"));
    }

    private static int tokens(JsonNode usage, String field) {
        JsonNode value = usage.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() ? Math.max(0, value.asInt()) : 0;
    }

    private static JsonNode readObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = STRICT.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (IOException malformed) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
