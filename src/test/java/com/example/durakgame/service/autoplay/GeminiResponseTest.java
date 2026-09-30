package com.example.durakgame.service.autoplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeminiResponseTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsUsageMetadataAndFinishReason() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", "{\"type\":\"TAKE\"}"))),
                        "finishReason", "STOP")),
                "usageMetadata", Map.of(
                        "promptTokenCount", 1830,
                        "cachedContentTokenCount", 1024,
                        "candidatesTokenCount", 42,
                        "thoughtsTokenCount", 310,
                        "totalTokenCount", 2182)));

        GeminiResponse response = GeminiResponse.parse(objectMapper, body);

        assertEquals(new GeminiResponse.Usage(1830, 1024, 42, 310, 2182), response.usage());
        assertEquals("STOP", response.finishReason());
        assertEquals("TAKE", response.answer().path("type").asText());
    }

    @Test
    void missingUsageCountsAreZero() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text", "{\"type\":\"TAKE\"}"))))),
                "usageMetadata", Map.of("promptTokenCount", 900, "totalTokenCount", 950)));

        GeminiResponse response = GeminiResponse.parse(objectMapper, body);

        assertEquals(new GeminiResponse.Usage(900, 0, 0, 0, 950), response.usage());
        assertNull(response.finishReason());
    }

    @Test
    void neverTakesTheAnswerFromThoughtParts() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(
                                Map.of("text", "{\"type\":\"ATTACK\",\"cardCode\":\"AS\"}", "thought", true),
                                Map.of("text", "I think I will take."))),
                        "finishReason", "STOP"))));

        GeminiResponse response = GeminiResponse.parse(objectMapper, body);

        assertNull(response.answer(), "JSON inside a thought part is reasoning, not the answer");
        assertEquals(List.of("{\"type\":\"ATTACK\",\"cardCode\":\"AS\"}"), response.thoughts());
    }

    @Test
    void prefersTheRegularPartOverThoughtParts() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(
                                Map.of("text", "{\"type\":\"TAKE\"}", "thought", true),
                                Map.of("text", "```json\n{\"type\":\"ATTACK\",\"cardCode\":\"7S\"}\n```")))))));

        GeminiResponse response = GeminiResponse.parse(objectMapper, body);

        assertNotNull(response.answer());
        assertEquals("7S", response.answer().path("cardCode").asText());
    }

    @Test
    void joinsAnAnswerSplitAcrossTextParts() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(
                                Map.of("text", "{\"type\":\"ATT"),
                                Map.of("text", "ACK\",\"cardCode\":\"6D\"}")))))));

        GeminiResponse response = GeminiResponse.parse(objectMapper, body);

        assertEquals("ATTACK", response.answer().path("type").asText());
    }

    @Test
    void reportsPromptBlocksAndApiErrors() throws Exception {
        GeminiResponse blocked = GeminiResponse.parse(objectMapper, objectMapper.writeValueAsString(Map.of(
                "promptFeedback", Map.of("blockReason", "SAFETY"))));
        assertEquals("PROMPT_BLOCKED_SAFETY", blocked.finishReason());
        assertNull(blocked.answer());

        GeminiResponse error = GeminiResponse.parse(objectMapper, objectMapper.writeValueAsString(Map.of(
                "error", Map.of("code", 429, "status", "RESOURCE_EXHAUSTED", "message", "Quota exceeded"))));
        assertEquals("RESOURCE_EXHAUSTED", error.errorStatus());
        assertEquals("Quota exceeded", error.errorMessage());
    }

    @Test
    void toleratesGarbage() {
        assertEquals(GeminiResponse.EMPTY, GeminiResponse.parse(objectMapper, "<html>502</html>"));
        assertEquals(GeminiResponse.EMPTY, GeminiResponse.parse(objectMapper, ""));
        assertNull(GeminiResponse.parse(objectMapper, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + "\"I would attack with something low.\"}]}}]}").answer());
    }
}
