package com.example.durakgame.service.autoplay;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Suit;
import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiAutoPlayDecisionEngineTest {
    static final String API_KEY = "test-key-7f3a";
    static final String BOT = "5b0c7d0e-bot0-4c6f-9a70-6d3e2f1a0b11";
    static final String HUMAN = "8e2d1c3b-hum0-4f5a-8b7c-1a2b3c4d5e6f";
    static final String HUMAN_NAME = "Mallory Ignore-Previous-Instructions";
    static final String BOT_NAME = "Robo Elektronik";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HeuristicAutoPlayDecisionEngine heuristic = new HeuristicAutoPlayDecisionEngine();
    private final ScriptedTransport transport = new ScriptedTransport();
    private final long[] clock = {0L};
    private ListAppender<ILoggingEvent> logs;
    private Logger engineLogger;

    @BeforeEach
    void captureLogs() {
        engineLogger = (Logger) LoggerFactory.getLogger(GeminiAutoPlayDecisionEngine.class);
        logs = new ListAppender<>();
        logs.start();
        engineLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        engineLogger.detachAppender(logs);
    }

    /* ------------------------------------------------------------------ model & request contract */

    @Test
    void defaultsToGemini38FlashWithModernRequestFeatures() {
        GeminiAutoPlayDecisionEngine engine = engine(settings());

        assertEquals(URI.create("http://localhost/v1beta/models/gemini-3.8-flash:generateContent"), engine.endpoint());
        Map<String, Object> config = engine.generationConfig();
        assertEquals("application/json", config.get("responseMimeType"));
        assertEquals(Map.of("thinkingLevel", "HIGH"), config.get("thinkingConfig"));
        assertFalse(config.containsKey("temperature"));
    }

    @Test
    void gemini37KeepsTheRequestShapeThatWorksToday() {
        GeminiAutoPlayDecisionEngine engine = engine(settings().model("gemini-3.7-flash"));

        assertEquals(URI.create("http://localhost/v1beta/models/gemini-3.7-flash:generateContent"), engine.endpoint());
        assertEquals("application/json", engine.generationConfig().get("responseMimeType"));
        assertEquals(Map.of("thinkingLevel", "HIGH"), engine.generationConfig().get("thinkingConfig"));
    }

    @Test
    void generationConfigOmitsDeprecatedSamplingParametersForGeminiThreeAndNewer() {
        for (String model : List.of("gemini-3.7-flash", "gemini-3.8-flash", "gemini-3.9-pro", "gemini-4.0-flash")) {
            Map<String, Object> generationConfig = engine(settings().model(model)).generationConfig();

            assertFalse(generationConfig.containsKey("temperature"), model);
            assertFalse(generationConfig.containsKey("topP"), model);
            assertFalse(generationConfig.containsKey("topK"), model);
            assertFalse(generationConfig.containsKey("candidateCount"), model);
            assertTrue(generationConfig.containsKey("thinkingConfig"), model);
        }
    }

    @Test
    void acceptsAModelsResourcePrefix() {
        GeminiAutoPlayDecisionEngine engine = engine(settings().model("models/gemini-3.8-flash").baseUrl("http://localhost/v1beta/"));

        assertEquals("gemini-3.8-flash", engine.modelId());
        assertEquals(URI.create("http://localhost/v1beta/models/gemini-3.8-flash:generateContent"), engine.endpoint());
    }

    @Test
    void nonGemini3ModelDoesNotReceiveThinkingConfigAutomatically() {
        Map<String, Object> generationConfig = engine(settings().model("gemini-2.5-flash")).generationConfig();

        assertEquals(0, generationConfig.get("temperature"));
        assertEquals("application/json", generationConfig.get("responseMimeType"));
        assertFalse(generationConfig.containsKey("thinkingConfig"));
    }

    @Test
    void capabilityOverridesStillWin() {
        Map<String, Object> generationConfig = engine(settings()
                .model("gemini-3.8-flash")
                .jsonModeFlag("false")
                .thinkingConfigFlag("false")).generationConfig();

        assertFalse(generationConfig.containsKey("responseMimeType"));
        assertFalse(generationConfig.containsKey("thinkingConfig"));
    }

    @Test
    void gemmaThreeGetsThePromptOnlyRequestShape() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings().model("gemma-3-27b-it"));
        transport.respond(200, answer("{\"type\":\"ATTACK\",\"cardCode\":\"7C\"}"));

        engine.choose(openingAttack(), BOT, openingAttack().computeViewerLegalMoves(BOT));

        JsonNode request = transport.lastRequest();
        assertTrue(request.path("systemInstruction").isMissingNode());
        assertEquals(0, request.path("generationConfig").path("temperature").asInt(-1));
        assertTrue(request.path("generationConfig").path("responseMimeType").isMissingNode());
        assertTrue(request.path("generationConfig").path("thinkingConfig").isMissingNode());
        assertTrue(request.path("contents").path(0).path("parts").path(0).path("text").asText()
                .contains("Budgeted reasoning"));
    }

    @Test
    void sendsTheApiKeyOnlyInTheHeaderOverRealHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> seenKey = new AtomicReference<>();
        AtomicReference<String> seenPath = new AtomicReference<>();
        server.createContext("/", exchange -> {
            seenKey.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            seenPath.set(exchange.getRequestURI().toString());
            exchange.getRequestBody().readAllBytes();
            byte[] body = answer("{\"type\":\"ATTACK\",\"cardCode\":\"7C\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            GeminiAutoPlayDecisionEngine engine = new GeminiAutoPlayDecisionEngine(heuristic, objectMapper,
                    settings().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta").build(),
                    GeminiTransport.jdk(Duration.ofSeconds(2)), System::nanoTime);
            Game game = openingAttack();

            AutoPlayAction action = engine.choose(game, BOT, game.computeViewerLegalMoves(BOT));

            assertEquals(AutoPlayAction.attack("7C"), action);
            assertEquals(API_KEY, seenKey.get());
            assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", seenPath.get());
        } finally {
            server.stop(0);
        }
    }

    /* ------------------------------------------------------------------ adaptive thinking */

    @Test
    void decisionsWithAtMostTwoOptionsUseTheSimpleThinkingLevel() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings());

        // One matching throw-in card or pass.
        Game throwIn = throwInWithOneMatchingCard();
        transport.respond(200, answer("{\"type\":\"END_ROUND\"}"));
        assertEquals(AutoPlayAction.endRound(), engine.choose(throwIn, BOT, throwIn.computeViewerLegalMoves(BOT)));
        assertEquals("LOW", transport.lastRequest().path("generationConfig").path("thinkingConfig")
                .path("thinkingLevel").asText());

        // Exactly one beating card or take.
        Game defence = defenceWithOneBeatingCard();
        transport.respond(200, answer("{\"type\":\"TAKE\"}"));
        assertEquals(AutoPlayAction.take(), engine.choose(defence, BOT, defence.computeViewerLegalMoves(BOT)));
        assertEquals("LOW", transport.lastRequest().path("generationConfig").path("thinkingConfig")
                .path("thinkingLevel").asText());
        assertTrue(logLines("autoplay_llm_call").getLast().contains(" thinkingLevel=LOW "));
    }

    @Test
    void decisionsWithMoreOptionsKeepTheConfiguredThinkingLevel() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings().thinkingLevel("MEDIUM").simpleThinkingLevel("MINIMAL"));
        Game game = openingAttack();
        assertTrue(AutoPlayLegality.optionCount(game.computeViewerLegalMoves(BOT)) > 2);

        transport.respond(200, answer("{\"type\":\"ATTACK\",\"cardCode\":\"7C\"}"));
        engine.choose(game, BOT, game.computeViewerLegalMoves(BOT));
        assertEquals("MEDIUM", transport.lastRequest().path("generationConfig").path("thinkingConfig")
                .path("thinkingLevel").asText());

        Game throwIn = throwInWithOneMatchingCard();
        transport.respond(200, answer("{\"type\":\"END_ROUND\"}"));
        engine.choose(throwIn, BOT, throwIn.computeViewerLegalMoves(BOT));
        assertEquals("MINIMAL", transport.lastRequest().path("generationConfig").path("thinkingConfig")
                .path("thinkingLevel").asText());
        assertEquals(Map.of("thinkingLevel", "MINIMAL"),
                engine.generationConfigForOptions(2).get("thinkingConfig"));
    }

    @Test
    void aSingleForcedOptionNeedsNoModelCall() {
        GeminiAutoPlayDecisionEngine engine = engine(settings());
        Game game = new GameBuilder()
                .player(BOT, BOT_NAME, true, "9D")
                .player(HUMAN, HUMAN_NAME, false, "QH", "8C")
                .talon()
                .build();

        AutoPlayAction action = engine.choose(game, BOT, game.computeViewerLegalMoves(BOT));

        assertEquals(AutoPlayAction.attack("9D"), action);
        assertEquals(0, transport.calls());
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=forced reason=single_legal_option "));
    }

    /* ------------------------------------------------------------------ validation */

    @Test
    void normalizesTheModelsCardCodes() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        transport.respond(200, answer("{\"type\":\"attack\",\"cardCode\":\"7c\"}"));

        assertEquals(AutoPlayAction.attack("7C"), engine.choose(game, BOT, game.computeViewerLegalMoves(BOT)));
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=llm reason=none action=ATTACK card=7C "));
    }

    @Test
    void illegalModelActionsFallBackToTheHeuristic() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings());
        Game game = throwInWithOneMatchingCard();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        AutoPlayAction heuristicMove = heuristic.choose(game, BOT, moves);

        transport.respond(200, answer("{\"type\":\"TAKE\"}"));
        assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        assertTrue(logLines("autoplay_decision").getLast()
                .contains(" source=heuristic reason=illegal_model_action "));
        assertTrue(logLines("autoplay_decision").getLast().endsWith(" model=TAKE/-/-"));

        transport.respond(200, answer("{\"type\":\"ATTACK\",\"cardCode\":\"AS\"}"));
        assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        assertTrue(logLines("autoplay_decision").getLast().endsWith(" model=ATTACK/AS/-"));
    }

    @Test
    void endRoundIsOnlyAcceptedWhenLegal() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        transport.respond(200, answer("{\"type\":\"END_ROUND\"}"));

        AutoPlayAction action = engine.choose(game, BOT, moves);

        assertEquals(heuristic.choose(game, BOT, moves), action);
        assertTrue(AutoPlayLegality.isLegal(action, moves));
    }

    @Test
    void answersOnlyFoundInThoughtPartsAreUnparseable() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        transport.respond(200, objectMapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "content", Map.of("parts", List.of(
                        Map.of("text", "{\"type\":\"ATTACK\",\"cardCode\":\"10D\"}", "thought", true))),
                "finishReason", "MAX_TOKENS")))));

        assertEquals(heuristic.choose(game, BOT, moves), engine.choose(game, BOT, moves));
        assertTrue(logLines("autoplay_decision").getLast().contains(" reason=unparseable "));
        assertTrue(logLines("autoplay_llm_call").getLast().contains(" finishReason=MAX_TOKENS "));
    }

    @Test
    void transportFailuresFallBackToTheHeuristic() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        AutoPlayAction heuristicMove = heuristic.choose(game, BOT, moves);

        transport.fail(new HttpTimeoutException("request timed out"));
        assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        assertTrue(logLines("autoplay_llm_call").getLast().contains(" httpStatus=0 "));
        assertTrue(logLines("autoplay_llm_call").getLast().contains(" error=timeout"));

        transport.respond(503, "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"overloaded\"}}");
        assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        assertTrue(logLines("autoplay_llm_call").getLast()
                .contains(" httpStatus=503 "));
        assertTrue(logLines("autoplay_llm_call").getLast()
                .endsWith(" error=http_status errorStatus=UNAVAILABLE errorMessage=\"overloaded\""));
        assertTrue(logLines("autoplay_decision").getLast().contains(" reason=primary_model_failed "));
    }

    @Test
    void disabledEngineUsesTheHeuristicWithoutCallingTheModel() {
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);

        assertEquals(heuristic.choose(game, BOT, moves), engine(settings().enabled(false)).choose(game, BOT, moves));
        assertEquals(heuristic.choose(game, BOT, moves), engine(settings().apiKey(" ")).choose(game, BOT, moves));
        assertEquals(0, transport.calls());
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=heuristic reason=disabled "));
    }

    /* ------------------------------------------------------------------ resilience and spend caps */

    @Test
    void repeatedTransientFailuresOpenTheCircuitUntilAProbeSucceeds() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings()
                .circuitBreakerFailureThreshold(3)
                .circuitBreakerCooldownMs(60_000));
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        AutoPlayAction heuristicMove = heuristic.choose(game, BOT, moves);
        transport.fail(new HttpTimeoutException("slow"));
        transport.respond(503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
        transport.respond(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");

        for (int i = 0; i < 3; i++) {
            assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        }
        assertEquals(LlmCircuitBreaker.State.OPEN, engine.circuitState());

        assertEquals(heuristicMove, engine.choose(game, BOT, moves));
        assertEquals(3, transport.calls(), "an open circuit skips the model");
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=heuristic reason=circuit_open "));

        clock[0] += Duration.ofSeconds(60).toNanos();
        transport.respond(200, answer("{\"type\":\"ATTACK\",\"cardCode\":\"10D\"}"));
        assertEquals(AutoPlayAction.attack("10D"), engine.choose(game, BOT, moves));
        assertEquals(4, transport.calls(), "one probe after the cooldown");
        assertEquals(LlmCircuitBreaker.State.CLOSED, engine.circuitState());
    }

    @Test
    void clientErrorsAndBadAnswersDoNotOpenTheCircuit() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings().circuitBreakerFailureThreshold(2));
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        transport.respond(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}");
        transport.respond(404, "{\"error\":{\"status\":\"NOT_FOUND\"}}");
        transport.respond(200, answer("not json at all"));
        transport.respond(200, answer("{\"type\":\"TAKE\"}"));

        for (int i = 0; i < 4; i++) {
            engine.choose(game, BOT, moves);
        }

        assertEquals(4, transport.calls());
        assertEquals(LlmCircuitBreaker.State.CLOSED, engine.circuitState());
    }

    @Test
    void theGlobalCallBudgetCapsModelCallsAcrossGames() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings().maxCallsPerMinute(6));
        Game first = openingAttack();
        Game second = throwInWithOneMatchingCard();
        transport.respond(200, answer("{\"type\":\"ATTACK\",\"cardCode\":\"7C\"}"));

        assertEquals(AutoPlayAction.attack("7C"), engine.choose(first, BOT, first.computeViewerLegalMoves(BOT)));
        ViewerLegalMoves secondMoves = second.computeViewerLegalMoves(BOT);
        assertEquals(heuristic.choose(second, BOT, secondMoves), engine.choose(second, BOT, secondMoves));
        assertEquals(1, transport.calls());
        assertTrue(logLines("autoplay_decision").getLast().contains(" source=heuristic reason=budget_exhausted "));

        clock[0] += Duration.ofSeconds(11).toNanos();
        transport.respond(200, answer("{\"type\":\"END_ROUND\"}"));
        assertEquals(AutoPlayAction.endRound(), engine.choose(second, BOT, secondMoves));
        assertEquals(2, transport.calls());
    }

    @Test
    void anExhaustedBudgetDoesNotStrandTheHalfOpenProbe() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings()
                .circuitBreakerFailureThreshold(1)
                .circuitBreakerCooldownMs(1_000)
                .maxCallsPerMinute(6));
        Game game = openingAttack();
        ViewerLegalMoves moves = game.computeViewerLegalMoves(BOT);
        transport.respond(500, "{}");
        engine.choose(game, BOT, moves);
        assertEquals(LlmCircuitBreaker.State.OPEN, engine.circuitState());

        clock[0] += Duration.ofSeconds(2).toNanos();
        engine.choose(game, BOT, moves);
        assertTrue(logLines("autoplay_decision").getLast().contains(" reason=budget_exhausted "));

        clock[0] += Duration.ofSeconds(10).toNanos();
        transport.respond(200, answer("{\"type\":\"ATTACK\",\"cardCode\":\"7C\"}"));
        assertEquals(AutoPlayAction.attack("7C"), engine.choose(game, BOT, moves));
        assertEquals(LlmCircuitBreaker.State.CLOSED, engine.circuitState());
    }

    /* ------------------------------------------------------------------ logging */

    @Test
    void logsOneSingleLineEntryPerCallAndPerDecisionWithTokenUsage() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        transport.onCall(() -> clock[0] += 1_234_000_000L);
        transport.respond(200, objectMapper.writeValueAsString(Map.of(
                "candidates", List.of(Map.of(
                        "content", Map.of("parts", List.of(Map.of("text",
                                "{\"strategy\":\"lead low\",\"type\":\"ATTACK\",\"cardCode\":\"7C\"}"))),
                        "finishReason", "STOP")),
                "usageMetadata", Map.of(
                        "promptTokenCount", 2100, "cachedContentTokenCount", 1536, "candidatesTokenCount", 37,
                        "thoughtsTokenCount", 512, "totalTokenCount", 2649))));

        engine.choose(game, BOT, game.computeViewerLegalMoves(BOT));

        List<String> calls = logLines("autoplay_llm_call");
        List<String> decisions = logLines("autoplay_decision");
        assertEquals(1, calls.size());
        assertEquals(1, decisions.size());
        assertEquals("autoplay_llm_call code=TEST01 player=" + BOT + " model=gemini-3.8-flash thinkingLevel=HIGH "
                + "latencyMs=1234 httpStatus=200 promptTokens=2100 cachedTokens=1536 outputTokens=37 "
                + "thoughtTokens=512 totalTokens=2649 finishReason=STOP error=none", calls.getFirst());
        assertEquals("autoplay_decision code=TEST01 player=" + BOT + " source=llm reason=none action=ATTACK "
                + "card=7C attackCard=- options=4", decisions.getFirst());
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains("\n"), "log entries must be single-line: " + line);
            assertFalse(line.contains(API_KEY), "API key leaked: " + line);
            assertFalse(line.contains(HUMAN_NAME) || line.contains(BOT_NAME), "player name leaked: " + line);
        }
    }

    @Test
    void errorMessagesNeverEchoTheApiKey() throws Exception {
        GeminiAutoPlayDecisionEngine engine = engine(settings());
        Game game = openingAttack();
        transport.respond(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"bad key " + API_KEY
                + "\\nsecond line\"}}");

        engine.choose(game, BOT, game.computeViewerLegalMoves(BOT));

        String line = logLines("autoplay_llm_call").getLast();
        assertFalse(line.contains(API_KEY));
        assertFalse(line.contains("\n"));
        assertTrue(line.contains("errorMessage=\"bad key [redacted] second line\""));
    }

    /* ------------------------------------------------------------------ helpers */

    GeminiSettings.Builder settings() {
        return GeminiSettings.builder()
                .enabled(true)
                .apiKey(API_KEY)
                .baseUrl("http://localhost/v1beta")
                .requestTimeoutMs(1000);
    }

    GeminiAutoPlayDecisionEngine engine(GeminiSettings.Builder settings) {
        return new GeminiAutoPlayDecisionEngine(heuristic, objectMapper, settings.build(), transport, () -> clock[0]);
    }

    List<String> logLines(String event) {
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith(event + " "))
                .toList();
    }

    String answer(String answerJson) throws IOException {
        return objectMapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "content", Map.of("parts", List.of(Map.of("text", answerJson))),
                "finishReason", "STOP"))));
    }

    /** The bot leads a fresh bout with four cards. */
    static Game openingAttack() {
        return new GameBuilder()
                .player(BOT, BOT_NAME, true, "7C", "10D", "QS", "AH")
                .player(HUMAN, HUMAN_NAME, false, "QH", "8C", "9D", "6S")
                .talon("6D", "7D", "JC", "KS")
                .build();
    }

    /** All attacks are beaten; the bot holds exactly one matching card (options: throw it in or pass). */
    static Game throwInWithOneMatchingCard() {
        return new GameBuilder()
                .player(BOT, BOT_NAME, true, "7S", "KC", "10D")
                .player(HUMAN, HUMAN_NAME, false, "QH", "8C", "9D")
                .talon("6D", "7D", "JC", "KS")
                .defended("7H", "9H", BOT)
                .build();
    }

    /** The human attacks; the bot can beat the card with exactly one card (options: defend or take). */
    static Game defenceWithOneBeatingCard() {
        return new GameBuilder()
                .player(HUMAN, HUMAN_NAME, false, "QH", "8C", "9D")
                .player(BOT, BOT_NAME, true, "KC", "6D", "9H")
                .talon("6D", "7D", "JC", "KS")
                .undefended("7C", HUMAN)
                .build();
    }

    /** Snapshot builder: seat 0 attacks seat 1, trump is spades. */
    static final class GameBuilder {
        private final List<Game.PlayerSnapshot> players = new ArrayList<>();
        private final List<Game.AttackSnapshot> table = new ArrayList<>();
        private final List<Game.KnownCardsSnapshot> known = new ArrayList<>();
        private List<Card> talon = List.of();
        private List<Card> discarded = List.of();
        private int attacker = 0;
        private int defender = 1;
        private int bouts = 0;
        private boolean taking;
        private int takeLimit;

        GameBuilder player(String id, String name, boolean bot, String... hand) {
            return player(id, name, bot, null, hand);
        }

        GameBuilder player(String id, String name, boolean bot, Integer team, String... hand) {
            players.add(new Game.PlayerSnapshot(id, name, players.size(), bot, team, cards(hand), "secret-" + id));
            return this;
        }

        GameBuilder talon(String... codes) {
            talon = cards(codes);
            return this;
        }

        GameBuilder discarded(String... codes) {
            discarded = cards(codes);
            return this;
        }

        GameBuilder known(String playerId, String... codes) {
            known.add(new Game.KnownCardsSnapshot(playerId, cards(codes)));
            return this;
        }

        GameBuilder roles(int attackerSeat, int defenderSeat) {
            attacker = attackerSeat;
            defender = defenderSeat;
            return this;
        }

        GameBuilder bouts(int completed) {
            bouts = completed;
            return this;
        }

        GameBuilder taking(int limit) {
            taking = true;
            takeLimit = limit;
            return this;
        }

        GameBuilder defended(String attack, String defence, String attackerId) {
            table.add(new Game.AttackSnapshot(Card.fromCode(attack), Card.fromCode(defence), attackerId));
            return this;
        }

        GameBuilder undefended(String attack, String attackerId) {
            table.add(new Game.AttackSnapshot(Card.fromCode(attack), null, attackerId));
            return this;
        }

        Game build() {
            return Game.fromSnapshot(new Game.Snapshot(
                    "TEST01", 0L, 0L, 0L, players.getFirst().id(), GameStatus.IN_PROGRESS,
                    Suit.SPADES, Card.fromCode("KS"), attacker, defender, null, taking, takeLimit, 0L,
                    players, talon, table, Set.of(), discarded, known, false, null, null, bouts));
        }

        private static List<Card> cards(String... codes) {
            return Arrays.stream(codes).map(Card::fromCode).toList();
        }
    }

    /** Returns queued responses (or throws queued failures) and records every request body. */
    final class ScriptedTransport implements GeminiTransport {
        private final Deque<Object> script = new ArrayDeque<>();
        private final List<String> requests = new ArrayList<>();
        private Runnable onCall = () -> {
        };

        void respond(int status, String body) {
            script.add(new HttpResult(status, body));
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

        @Override
        public HttpResult post(URI endpoint, String apiKey, String jsonBody, Duration timeout) throws IOException {
            requests.add(jsonBody);
            onCall.run();
            assertEquals(API_KEY, apiKey);
            Object next = script.poll();
            assertNotNull(next, "unexpected model call");
            if (next instanceof IOException failure) {
                throw failure;
            }
            return (HttpResult) next;
        }
    }
}
