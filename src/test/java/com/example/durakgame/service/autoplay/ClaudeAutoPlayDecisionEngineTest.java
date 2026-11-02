package com.example.durakgame.service.autoplay;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.BOT;
import static com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.BOT_NAME;
import static com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.HUMAN;
import static com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.HUMAN_NAME;
import static com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.defenceWithOneBeatingCard;
import static com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.openingAttack;
import static com.example.durakgame.service.autoplay.GeminiAutoPlayDecisionEngineTest.throwInWithOneMatchingCard;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeAutoPlayDecisionEngineTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HeuristicAutoPlayDecisionEngine heuristic = new HeuristicAutoPlayDecisionEngine();
    private final ScriptedTransport transport = new ScriptedTransport();
    private final long[] clock = {0L};
    private ListAppender<ILoggingEvent> logs;
    private Logger engineLogger;

    @BeforeEach
    void captureLogs() {
        engineLogger = (Logger) LoggerFactory.getLogger(ClaudeAutoPlayDecisionEngine.class);
        logs = new ListAppender<>();
        logs.start();
        engineLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        engineLogger.detachAppender(logs);
    }

    /* ------------------------------------------------------------------ request contract */

    @Test
    void requestFollowsTheMessagesContract() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        transport.respond(200, message("{\"strategy\":\"lead low\",\"type\":\"ATTACK\",\"cardCode\":\"7C\","
                + "\"attackCardCode\":null,\"defensePlan\":[]}"));

        engine.choose(game, BOT, game.computeViewerLegalMoves(BOT));

        JsonNode request = transport.lastRequest();
        assertEquals(List.of("model", "max_tokens", "system", "messages", "output_config", "inference_geo"),
                fieldNames(request), "no temperature, thinking, tools, fallbacks or other extras");
        assertEquals("claude-opus-5-5", request.path("model").asText());
        assertEquals(8192, request.path("max_tokens").asInt());
        assertEquals(MovePrompt.SYSTEM_INSTRUCTION, request.path("system").asText());
        assertEquals(1, request.path("messages").size());
        JsonNode message = request.path("messages").path(0);
        assertEquals("user", message.path("role").asText());
        JsonNode content = message.path("content");
        assertEquals(2, content.size());
        assertEquals("text", content.path(0).path("type").asText());
        assertTrue(content.path(0).path("text").asText().startsWith("Choose the next move for the seat \"you\""));
        assertTrue(content.path(0).path("text").asText().endsWith("Game state:\n"));
        assertEquals(objectMapper.readTree("{\"type\":\"ephemeral\"}"), content.path(0).path("cache_control"));
        assertEquals("text", content.path(1).path("type").asText());
        assertTrue(content.path(1).path("cache_control").isMissingNode(), "the per-turn state is not cached");
        JsonNode state = objectMapper.readTree(content.path(1).path("text").asText());
        assertEquals(4, state.path("ownHand").size());
        assertEquals("medium", request.path("output_config").path("effort").asText());
        assertEquals("json_schema", request.path("output_config").path("format").path("type").asText());
        assertEquals(ClaudeAutoPlayDecisionEngine.answerSchema(objectMapper),
                request.path("output_config").path("format").path("schema"));
        assertEquals("global", request.path("inference_geo").asText());
    }

    @Test
    void answerSchemaIsClosedAndUsesOnlyWhatStructuredOutputsEnforce() {
        JsonNode schema = ClaudeAutoPlayDecisionEngine.answerSchema(objectMapper);

        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertEquals(List.of("strategy", "type", "cardCode", "attackCardCode", "defensePlan"),
                textValues(schema.path("required")));
        assertEquals(List.of("ATTACK", "DEFEND", "TRANSFER", "TAKE", "END_ROUND"),
                textValues(schema.path("properties").path("type").path("enum")));
        JsonNode cardCode = schema.path("properties").path("cardCode").path("anyOf");
        assertEquals("null", cardCode.path(1).path("type").asText());
        List<String> deck = textValues(cardCode.path(0).path("enum"));
        assertEquals(36, new HashSet<>(deck).size());
        assertTrue(deck.containsAll(List.of("6C", "10D", "QH", "AS")));
        JsonNode pair = schema.path("properties").path("defensePlan").path("items");
        assertFalse(pair.path("additionalProperties").asBoolean(true));
        assertEquals(List.of("attackCardCode", "cardCode"), textValues(pair.path("required")));
        assertEquals(deck, textValues(pair.path("properties").path("cardCode").path("enum")));
        for (String unsupported : List.of("minItems", "maxItems", "minLength", "maxLength", "minimum", "maximum")) {
            assertTrue(schema.findValues(unsupported).isEmpty(), unsupported + " is not enforced by structured outputs");
        }
    }

    @Test
    void requestsShareAByteIdenticalPrefixAndCarryNoNamesOrIds() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings());
        Game first = openingAttack();
        Game second = defenceWithOneBeatingCard();

        transport.respond(200, message("{\"strategy\":\"s\",\"type\":\"ATTACK\",\"cardCode\":\"7C\","
                + "\"attackCardCode\":null,\"defensePlan\":[]}"));
        engine.choose(first, BOT, first.computeViewerLegalMoves(BOT));
        JsonNode firstRequest = transport.lastRequest();
        String firstRaw = transport.lastRawRequest();
        transport.respond(200, message("{\"strategy\":\"s\",\"type\":\"TAKE\",\"cardCode\":null,"
                + "\"attackCardCode\":null,\"defensePlan\":[]}"));
        engine.choose(second, BOT, second.computeViewerLegalMoves(BOT));
        JsonNode secondRequest = transport.lastRequest();
        String secondRaw = transport.lastRawRequest();

        assertEquals(firstRequest.path("system"), secondRequest.path("system"));
        JsonNode firstContent = firstRequest.path("messages").path(0).path("content");
        JsonNode secondContent = secondRequest.path("messages").path(0).path("content");
        assertEquals(firstContent.path(0), secondContent.path(0), "the cached prefix is identical");
        assertNotEquals(firstContent.path(1), secondContent.path(1));
        for (String raw : List.of(firstRaw, secondRaw)) {
            for (String secret : List.of(HUMAN_NAME, BOT_NAME, HUMAN, BOT)) {
                assertFalse(raw.contains(secret), "request leaked " + secret);
            }
        }
    }

    @Test
    void decisionsWithAtMostTwoOptionsUseTheSimpleEffort() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings());
        Game throwIn = throwInWithOneMatchingCard();
        transport.respond(200, message("{\"strategy\":\"s\",\"type\":\"END_ROUND\",\"cardCode\":null,"
                + "\"attackCardCode\":null,\"defensePlan\":[]}"));

        assertEquals(AutoPlayAction.endRound(), engine.choose(throwIn, BOT, throwIn.computeViewerLegalMoves(BOT)));
        assertEquals("low", transport.lastRequest().path("output_config").path("effort").asText());
        assertTrue(logLines("autoplay_llm_call").getLast().contains(" effort=low "));

        ClaudeAutoPlayDecisionEngine sameEffort = engine(settings().effort("HIGH").simpleEffort(" "));
        assertEquals("high", sameEffort.effortFor(2));
        assertEquals("high", sameEffort.effortFor(5));
    }

    /* ------------------------------------------------------------------ answers */

    @Test
    void playsAValidAnswerAndIgnoresThinkingBlocks() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings());
        Game game = defenceWithOneBeatingCard();
        transport.respond(200, message("{\"strategy\":\"beat it\",\"type\":\"DEFEND\",\"cardCode\":\"KC\","
                + "\"attackCardCode\":\"7C\",\"defensePlan\":[{\"attackCardCode\":\"7C\",\"cardCode\":\"KC\"}]}"));

        assertEquals(AutoPlayAction.defend("7C", "KC"), engine.choose(game, BOT, game.computeViewerLegalMoves(BOT)));
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=llm reason=none action=DEFEND "));
    }

    @Test
    void refusalsAndCutOffAnswersFallBackWithoutOpeningTheCircuit() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings().circuitBreakerFailureThreshold(2));
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        AutoPlayAction heuristicMove = heuristic.choose(game, BOT, moves);

        transport.respond(200, response("refusal", Map.of("type", "refusal", "category", "cyber"), ""));
        assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=heuristic reason=refusal "));
        assertTrue(logLines("autoplay_llm_call").getLast().endsWith(" error=none stopCategory=cyber"));

        transport.respond(200, response("max_tokens", null, "{\"strategy\":\"I need to think about"));
        assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=heuristic reason=max_tokens "));

        transport.respond(200, response("end_turn", null, "not json"));
        assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=heuristic reason=unparseable "));

        assertEquals(LlmCircuitBreaker.State.CLOSED, engine.circuitState());
    }

    @Test
    void illegalAnswersFallBackToTheHeuristic() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        transport.respond(200, message("{\"strategy\":\"s\",\"type\":\"ATTACK\",\"cardCode\":\"KD\","
                + "\"attackCardCode\":null,\"defensePlan\":[]}"));

        assertEquals(heuristic.choose(game, BOT, moves), engine.choose(game, BOT, moves));
        assertTrue(logLines("autoplay_decision").getLast()
                .contains(" source=heuristic reason=illegal_model_action ")
                && logLines("autoplay_decision").getLast().endsWith(" model=ATTACK/KD/-"));
    }

    /* ------------------------------------------------------------------ failures and the circuit */

    @Test
    void throttlesOutagesAndRefusedCredentialsOpenTheCircuit() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings().circuitBreakerFailureThreshold(5));
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        AutoPlayAction heuristicMove = heuristic.choose(game, BOT, moves);
        transport.respond(429, error("rate_limit_error", "slow down"));
        transport.respond(529, error("overloaded_error", "Overloaded"));
        transport.respond(401, error("authentication_error", "invalid bearer token"));
        transport.respond(403, error("permission_error", "not allowed"));
        transport.fail(new AnthropicFederation.Unavailable(AnthropicFederation.Failure.DENIED));

        for (int i = 0; i < 5; i++) {
            assertEquals(heuristicMove, engine.choose(game, BOT, moves));
            assertTrue(logLines("autoplay_decision").getLast().contains(" reason=primary_model_failed "));
        }

        assertEquals(LlmCircuitBreaker.State.OPEN, engine.circuitState());
        List<String> calls = logLines("autoplay_llm_call");
        assertTrue(calls.get(1).contains(" httpStatus=529 ")
                && calls.get(1).endsWith(" error=http_status errorType=overloaded_error errorMessage=\"Overloaded\""));
        assertTrue(calls.getLast().contains(" httpStatus=0 ") && calls.getLast().endsWith(" error=federation_denied"));
        assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        assertEquals(5, transport.calls(), "an open circuit skips the model");

        clock[0] += Duration.ofSeconds(60).toNanos();
        transport.respond(200, message("{\"strategy\":\"s\",\"type\":\"ATTACK\",\"cardCode\":\"10D\","
                + "\"attackCardCode\":null,\"defensePlan\":[]}"));
        assertEquals(AutoPlayAction.attack("10D"), engine.choose(game, BOT, moves));
        assertEquals(LlmCircuitBreaker.State.CLOSED, engine.circuitState());
    }

    @Test
    void timeoutsAndIoErrorsFallBackToTheHeuristic() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        transport.fail(new HttpTimeoutException("slow"));
        transport.fail(new IOException("reset"));

        engine.choose(game, BOT, moves);
        assertTrue(logLines("autoplay_llm_call").getLast().endsWith(" error=timeout"));
        engine.choose(game, BOT, moves);
        assertTrue(logLines("autoplay_llm_call").getLast().endsWith(" error=io_error"));
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=heuristic reason=primary_model_failed "));
    }

    @Test
    void requestErrorsDoNotOpenTheCircuit() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings().circuitBreakerFailureThreshold(2));
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        transport.respond(400, error("invalid_request_error", "output_config.format: bad schema\nsecond line"));
        transport.respond(404, error("not_found_error", "model: claude-nope"));
        transport.respond(413, error("request_too_large", "too large"));

        for (int i = 0; i < 3; i++) {
            engine.choose(game, BOT, moves);
        }

        assertEquals(3, transport.calls());
        assertEquals(LlmCircuitBreaker.State.CLOSED, engine.circuitState());
        assertTrue(logLines("autoplay_llm_call").getFirst().endsWith(" error=http_status "
                + "errorType=invalid_request_error errorMessage=\"output_config.format: bad schema second line\""));
    }

    @Test
    void withoutFederationIdsOrWhenDisabledTheModelIsNeverCalled() {
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        AutoPlayAction heuristicMove = heuristic.choose(game, BOT, moves);

        assertEquals(heuristicMove, engine(settings(), false).choose(game, BOT, moves));
        assertEquals(heuristicMove, engine(settings().enabled(false), true).choose(game, BOT, moves));

        assertEquals(0, transport.calls());
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=heuristic reason=disabled "));
        assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN
                && event.getFormattedMessage().startsWith("autoplay_claude_config enabled=true federationConfigured=false ")),
                "a provider switch without the ids is called out");
    }

    /* ------------------------------------------------------------------ settings */

    @Test
    void settingsTheApiWouldRejectStopStartup() {
        assertThrows(IllegalStateException.class, () -> engine(settings().effort("extreme")));
        assertThrows(IllegalStateException.class, () -> engine(settings().simpleEffort("none")));
        assertThrows(IllegalStateException.class, () -> engine(settings().inferenceGeo("eu")));
        assertThrows(IllegalStateException.class, () -> engine(settings().model(" ")));
        assertThrows(IllegalStateException.class, () -> engine(settings().maxTokens(0)));
    }

    @Test
    void anEmptyInferenceGeoLeavesTheFieldOut() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings().inferenceGeo("").model("claude-sonnet-5-5"));
        Game game = openingAttack();
        transport.respond(200, message("{\"strategy\":\"s\",\"type\":\"ATTACK\",\"cardCode\":\"7C\","
                + "\"attackCardCode\":null,\"defensePlan\":[]}"));

        engine.choose(game, BOT, game.computeViewerLegalMoves(BOT));

        assertTrue(transport.lastRequest().path("inference_geo").isMissingNode());
        assertEquals("claude-sonnet-5-5", transport.lastRequest().path("model").asText());
        assertEquals("claude-sonnet-5-5", engine.modelId());
    }

    /* ------------------------------------------------------------------ logging */

    @Test
    void logsOneSingleLineEntryPerCallWithCacheUsage() throws Exception {
        ClaudeAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        transport.onCall(() -> clock[0] += 2_345_000_000L);
        transport.respond(200, message("{\"strategy\":\"lead low\",\"type\":\"ATTACK\",\"cardCode\":\"7C\","
                + "\"attackCardCode\":null,\"defensePlan\":[]}"), "req_011CUx9\nforged=1");

        engine.choose(game, BOT, game.computeViewerLegalMoves(BOT));

        List<String> calls = logLines("autoplay_llm_call");
        assertEquals(1, calls.size());
        assertEquals("autoplay_llm_call code=TEST01 player=" + BOT + " model=claude-opus-5-5 effort=medium "
                + "latencyMs=2345 httpStatus=200 promptTokens=2110 cachedTokens=1786 cacheWriteTokens=12 "
                + "outputTokens=420 finishReason=end_turn requestId=req_011CUx9forged1 error=none", calls.getFirst());
        assertEquals("autoplay_decision code=TEST01 player=" + BOT + " source=llm reason=none action=ATTACK "
                + "card=7C attackCard=- options=4 strategy=\"lead low\"", logLines("autoplay_decision").getFirst());
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains("\n"), "log entries must be single-line: " + line);
            assertFalse(line.contains(HUMAN_NAME) || line.contains(BOT_NAME), "player name leaked: " + line);
        }
    }

    /* ------------------------------------------------------------------ helpers */

    ClaudeSettings.Builder settings() {
        return ClaudeSettings.builder().requestTimeoutMs(1000);
    }

    ClaudeAutoPlayDecisionEngine engine(ClaudeSettings.Builder settings) {
        return engine(settings, true);
    }

    ClaudeAutoPlayDecisionEngine engine(ClaudeSettings.Builder settings, boolean federationConfigured) {
        return new ClaudeAutoPlayDecisionEngine(heuristic, objectMapper, settings.build(), federationConfigured,
                transport, () -> clock[0]);
    }

    List<String> logLines(String event) {
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith(event + " "))
                .toList();
    }

    /** A completed Messages response: an (empty) thinking block, then the answer. */
    String message(String answerJson) throws IOException {
        return response("end_turn", null, answerJson);
    }

    String response(String stopReason, Map<String, Object> stopDetails, String text) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "msg_01");
        body.put("type", "message");
        body.put("role", "assistant");
        body.put("model", "claude-opus-5-5");
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "thinking", "thinking", "", "signature", "sig"));
        if (!text.isEmpty()) {
            content.add(Map.of("type", "text", "text", text));
        }
        body.put("content", content);
        body.put("stop_reason", stopReason);
        body.put("stop_details", stopDetails);
        body.put("usage", Map.of("input_tokens", 312, "cache_creation_input_tokens", 12,
                "cache_read_input_tokens", 1786, "output_tokens", 420));
        return objectMapper.writeValueAsString(body);
    }

    String error(String type, String message) throws IOException {
        return objectMapper.writeValueAsString(Map.of("type", "error",
                "error", Map.of("type", type, "message", message), "request_id", "req_err"));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    /** Returns queued responses (or throws queued failures) and records every request body. */
    final class ScriptedTransport implements AnthropicMessagesTransport {
        private final Deque<Object> script = new ArrayDeque<>();
        private final List<String> requests = new ArrayList<>();
        private Runnable onCall = () -> {
        };

        void respond(int status, String body) {
            respond(status, body, "req_01");
        }

        void respond(int status, String body, String requestId) {
            script.add(new HttpResult(status, body, requestId));
        }

        void fail(IOException failure) {
            script.add(failure);
        }

        void onCall(Runnable action) {
            onCall = action;
        }

        int calls() {
            return requests.size();
        }

        JsonNode lastRequest() throws IOException {
            return objectMapper.readTree(requests.getLast());
        }

        String lastRawRequest() {
            return requests.getLast();
        }

        @Override
        public HttpResult post(String jsonBody, Duration timeout) throws IOException {
            requests.add(jsonBody);
            onCall.run();
            assertEquals(Duration.ofMillis(1000), timeout);
            Object next = script.poll();
            assertNotNull(next, "unexpected model call");
            if (next instanceof IOException failure) {
                throw failure;
            }
            return (HttpResult) next;
        }
    }
}
