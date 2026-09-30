package com.example.durakgame.service.autoplay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The parts of a {@code generateContent} response the engine uses. The answer JSON is searched for
 * only in regular text parts: thought parts are reasoning, never the answer, and are kept solely for
 * debug logging.
 *
 * @param answer       the answer object, or {@code null} when no regular text part holds one
 * @param finishReason the first candidate's finish reason, or {@code PROMPT_BLOCKED_<reason>}
 * @param usage        token counts from {@code usageMetadata} (zero when absent)
 * @param thoughts     thought-summary texts, if the model returned any
 * @param errorStatus  {@code error.status} of an error response
 * @param errorMessage {@code error.message} of an error response
 */
record GeminiResponse(
        JsonNode answer,
        String finishReason,
        Usage usage,
        List<String> thoughts,
        String errorStatus,
        String errorMessage
) {
    record Usage(int promptTokens, int cachedTokens, int outputTokens, int thoughtTokens, int totalTokens) {
        static final Usage NONE = new Usage(0, 0, 0, 0, 0);
    }

    static final GeminiResponse EMPTY = new GeminiResponse(null, null, Usage.NONE, List.of(), null, null);

    static GeminiResponse parse(ObjectMapper objectMapper, String body) {
        JsonNode root;
        try {
            root = body == null || body.isBlank() ? null : objectMapper.readTree(body);
        } catch (IOException ex) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            return EMPTY;
        }
        JsonNode candidate = root.path("candidates").path(0);
        String finishReason = text(candidate.path("finishReason"));
        String blockReason = text(root.path("promptFeedback").path("blockReason"));
        if (finishReason == null && blockReason != null) {
            finishReason = "PROMPT_BLOCKED_" + blockReason;
        }
        List<String> texts = new ArrayList<>();
        List<String> thoughts = new ArrayList<>();
        JsonNode parts = candidate.path("content").path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                JsonNode text = part.path("text");
                if (!text.isTextual()) {
                    continue;
                }
                if (part.path("thought").asBoolean(false)) {
                    thoughts.add(text.asText());
                } else {
                    texts.add(text.asText());
                }
            }
        }
        JsonNode answer = null;
        for (int i = texts.size() - 1; i >= 0 && answer == null; i--) {
            answer = parseObject(objectMapper, texts.get(i));
        }
        if (answer == null && texts.size() > 1) {
            /* A single JSON answer may arrive split across several text parts. */
            answer = parseObject(objectMapper, String.join("", texts));
        }
        JsonNode error = root.path("error");
        return new GeminiResponse(answer, finishReason, usage(root.path("usageMetadata")), List.copyOf(thoughts),
                text(error.path("status")), text(error.path("message")));
    }

    private static Usage usage(JsonNode metadata) {
        if (!metadata.isObject()) {
            return Usage.NONE;
        }
        return new Usage(
                metadata.path("promptTokenCount").asInt(0),
                metadata.path("cachedContentTokenCount").asInt(0),
                metadata.path("candidatesTokenCount").asInt(0),
                metadata.path("thoughtsTokenCount").asInt(0),
                metadata.path("totalTokenCount").asInt(0));
    }

    /** Accepts a bare object, a fenced ```json block, a JSON string holding an object, or an object in prose. */
    static JsonNode parseObject(ObjectMapper objectMapper, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String text = rawText.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node != null && node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            if (node != null && node.isObject()) {
                return node;
            }
        } catch (IOException ignored) {
            // Fall through to extracting an object from surrounding text.
        }
        int left = text.indexOf('{');
        int right = text.lastIndexOf('}');
        if (left < 0 || right <= left) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(left, right + 1));
            return node != null && node.isObject() ? node : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }
}
