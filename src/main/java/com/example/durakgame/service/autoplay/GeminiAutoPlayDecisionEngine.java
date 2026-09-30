package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.AttackEntry;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Chooses bot moves with Gemini and falls back to {@link HeuristicAutoPlayDecisionEngine}.
 *
 * <p>Every returned action is legal for the {@link ViewerLegalMoves} passed in: model answers are
 * normalized and validated, and anything illegal is replaced by the heuristic's move. Each model
 * call and each decision produce one single-line {@code key=value} log entry.
 */
@Component
@Primary
public class GeminiAutoPlayDecisionEngine implements AutoPlayDecisionEngine {
    private static final Logger log = LoggerFactory.getLogger(GeminiAutoPlayDecisionEngine.class);

    static final String DEFAULT_MODEL = GeminiSettings.DEFAULT_MODEL;
    private static final long MAX_CONNECT_TIMEOUT_MS = 5000;
    private static final int LOG_TEXT_LIMIT = 300;

    private final HeuristicAutoPlayDecisionEngine fallback;
    private final ObjectMapper objectMapper;
    private final GeminiTransport transport;
    private final LongSupplier nanoClock;
    private final boolean enabled;
    private final String apiKey;
    private final GeminiModelProfile model;
    private final URI endpoint;
    private final String thinkingLevel;
    private final String simpleThinkingLevel;
    private final Duration timeout;
    private final long reasoningBudgetSeconds;
    private final boolean publicCardMemoryEnabled;
    private final boolean jsonModeSupported;
    private final boolean systemInstructionSupported;
    private final boolean thinkingConfigSupported;
    private final boolean promptReasoningBudgetUsed;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmCallBudget callBudget;
    private final DefencePlanCache planCache;

    @Autowired
    public GeminiAutoPlayDecisionEngine(
            HeuristicAutoPlayDecisionEngine fallback,
            ObjectMapper objectMapper,
            @Value("${autoplay.gemini.enabled:true}") boolean enabled,
            @Value("${autoplay.gemini.api-key:}") String apiKey,
            @Value("${autoplay.gemini.model:" + GeminiSettings.DEFAULT_MODEL + "}") String model,
            @Value("${autoplay.gemini.base-url:" + GeminiSettings.DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${autoplay.gemini.thinking-level:" + GeminiSettings.DEFAULT_THINKING_LEVEL + "}")
            String thinkingLevel,
            @Value("${autoplay.gemini.simple-thinking-level:" + GeminiSettings.DEFAULT_SIMPLE_THINKING_LEVEL + "}")
            String simpleThinkingLevel,
            @Value("${autoplay.gemini.public-card-memory-enabled:true}") boolean publicCardMemoryEnabled,
            @Value("${autoplay.gemini.reasoning-budget-seconds:30}") long reasoningBudgetSeconds,
            @Value("${autoplay.gemini.json-mode:auto}") String jsonModeFlag,
            @Value("${autoplay.gemini.system-instruction:auto}") String systemInstructionFlag,
            @Value("${autoplay.gemini.thinking-config:auto}") String thinkingConfigFlag,
            @Value("${autoplay.gemini.prompt-reasoning-budget:auto}") String promptReasoningBudgetFlag,
            @Value("${autoplay.request-timeout-ms:30000}") long timeoutMs,
            @Value("${autoplay.gemini.circuit-breaker.failure-threshold:"
                    + GeminiSettings.DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD + "}") int circuitBreakerFailureThreshold,
            @Value("${autoplay.gemini.circuit-breaker.cooldown-ms:"
                    + GeminiSettings.DEFAULT_CIRCUIT_BREAKER_COOLDOWN_MS + "}") long circuitBreakerCooldownMs,
            @Value("${autoplay.gemini.max-calls-per-minute:" + GeminiSettings.DEFAULT_MAX_CALLS_PER_MINUTE + "}")
            int maxCallsPerMinute
    ) {
        this(fallback, objectMapper, new GeminiSettings(enabled, apiKey, model, baseUrl, thinkingLevel,
                        simpleThinkingLevel, publicCardMemoryEnabled, reasoningBudgetSeconds, jsonModeFlag,
                        systemInstructionFlag, thinkingConfigFlag, promptReasoningBudgetFlag, timeoutMs,
                        circuitBreakerFailureThreshold, circuitBreakerCooldownMs, maxCallsPerMinute),
                GeminiTransport.jdk(Duration.ofMillis(Math.max(1, Math.min(MAX_CONNECT_TIMEOUT_MS, timeoutMs)))),
                System::nanoTime);
    }

    /** Package-private for tests: scripted transport and a controllable clock. */
    GeminiAutoPlayDecisionEngine(
            HeuristicAutoPlayDecisionEngine fallback,
            ObjectMapper objectMapper,
            GeminiSettings settings,
            GeminiTransport transport,
            LongSupplier nanoClock
    ) {
        this.fallback = fallback;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.nanoClock = nanoClock;
        this.enabled = settings.enabled();
        this.apiKey = settings.apiKey() == null ? "" : settings.apiKey().trim();
        this.model = GeminiModelProfile.parse(settings.model(), GeminiSettings.DEFAULT_MODEL);
        this.endpoint = endpointFor(settings.baseUrl(), model.id());
        this.thinkingLevel = blankToNull(settings.thinkingLevel());
        this.simpleThinkingLevel = blankToNull(settings.simpleThinkingLevel());
        this.timeout = Duration.ofMillis(Math.max(1, settings.requestTimeoutMs()));
        this.reasoningBudgetSeconds = Math.max(1L, settings.reasoningBudgetSeconds());
        this.publicCardMemoryEnabled = settings.publicCardMemoryEnabled();
        /* Model capabilities: derived from the model version, overridable per property (auto|true|false). */
        this.jsonModeSupported = resolveCapability(settings.jsonModeFlag(), model.defaultJsonMode());
        this.systemInstructionSupported = resolveCapability(settings.systemInstructionFlag(),
                model.defaultSystemInstruction());
        this.thinkingConfigSupported = resolveCapability(settings.thinkingConfigFlag(), model.defaultThinkingConfig());
        this.promptReasoningBudgetUsed = resolveCapability(settings.promptReasoningBudgetFlag(),
                model.defaultPromptReasoningBudget());
        this.circuitBreaker = new LlmCircuitBreaker(settings.circuitBreakerFailureThreshold(),
                Duration.ofMillis(Math.max(0, settings.circuitBreakerCooldownMs())), nanoClock);
        this.callBudget = new LlmCallBudget(settings.maxCallsPerMinute(), nanoClock);
        this.planCache = new DefencePlanCache(DefencePlanCache.DEFAULT_MAX_PLANS, DefencePlanCache.DEFAULT_TTL,
                nanoClock);
        log.info("autoplay_gemini_config enabled={} apiKeyPresent={} model={} modelFamily={} jsonMode={} "
                        + "systemInstruction={} thinkingConfig={} thinkingLevel={} simpleThinkingLevel={} "
                        + "temperature={} timeoutMs={} circuitBreakerFailureThreshold={} circuitBreakerCooldownMs={} "
                        + "maxCallsPerMinute={} callBurst={}",
                enabled, !apiKey.isBlank(), model.id(), model.describe(), jsonModeSupported,
                systemInstructionSupported, thinkingConfigSupported, thinkingLevel, simpleThinkingLevel,
                model.pinZeroTemperature() ? "0" : "default", timeout.toMillis(),
                settings.circuitBreakerFailureThreshold(), settings.circuitBreakerCooldownMs(),
                settings.maxCallsPerMinute(), callBudget.burst());
    }

    private static boolean resolveCapability(String flag, boolean autoDefault) {
        String normalized = flag == null ? "" : flag.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase("auto")) {
            return autoDefault;
        }
        return Boolean.parseBoolean(normalized);
    }

    private static URI endpointFor(String baseUrl, String modelId) {
        String base = baseUrl == null || baseUrl.isBlank() ? GeminiSettings.DEFAULT_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/models/" + modelId + ":generateContent");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public AutoPlayAction choose(Game game, String playerId, ViewerLegalMoves legalMoves) {
        List<AutoPlayAction> options = AutoPlayLegality.enumerate(legalMoves);
        Decision decision = decide(game, playerId, legalMoves, options);
        log.info("autoplay_decision code={} player={} source={} reason={} action={} card={} attackCard={} "
                        + "options={}{}",
                game.getCode(), playerId, decision.source(), decision.reason(), actionType(decision.action()),
                orDash(decision.action() == null ? null : decision.action().cardCode()),
                orDash(decision.action() == null ? null : decision.action().attackCardCode()),
                options.size(), decision.detail() == null ? "" : " " + decision.detail());
        return decision.action();
    }

    /** Where an action came from and, when the heuristic was used, why. */
    private record Decision(AutoPlayAction action, String source, String reason, String detail) {
        static Decision of(AutoPlayAction action, String source, String reason) {
            return new Decision(action, source, reason, null);
        }
    }

    private Decision decide(Game game, String playerId, ViewerLegalMoves legalMoves, List<AutoPlayAction> options) {
        if (options.isEmpty()) {
            return heuristic(game, playerId, legalMoves, "no_legal_moves");
        }
        if (options.size() == 1 && isForced(game, options.getFirst())) {
            return Decision.of(options.getFirst(), "forced", "single_legal_option");
        }
        if (!enabled || apiKey.isBlank()) {
            return heuristic(game, playerId, legalMoves, "disabled");
        }
        if (legalMoves.canDefend()) {
            /* The model may already have planned this defence; replay it while the table still matches. */
            AutoPlayAction planned = planCache.next(game, playerId, legalMoves);
            if (planned != null && AutoPlayLegality.isLegal(planned, legalMoves)) {
                return Decision.of(planned, "plan_cache", "none");
            }
        } else {
            planCache.discard(game.getCode(), playerId);
        }
        LlmCircuitBreaker.Permit permit = circuitBreaker.tryAcquire();
        if (permit == LlmCircuitBreaker.Permit.REJECTED) {
            return heuristic(game, playerId, legalMoves, "circuit_open");
        }
        if (!callBudget.tryAcquire()) {
            circuitBreaker.record(permit, LlmCircuitBreaker.Outcome.NEUTRAL);
            return heuristic(game, playerId, legalMoves, "budget_exhausted");
        }
        ModelCall call = callModel(game, playerId, legalMoves, options.size());
        circuitBreaker.record(permit, call.breakerOutcome());
        if (!call.completed()) {
            return heuristic(game, playerId, legalMoves, "primary_model_failed");
        }
        if (call.response().answer() == null) {
            return heuristic(game, playerId, legalMoves, "unparseable");
        }
        GeminiAnswer answer = new GeminiAnswer(call.response().answer());
        logStrategy(game, playerId, answer, call.response());
        Set<String> undefended = undefendedAttacks(game, legalMoves);
        AutoPlayAction action = answer.resolve(legalMoves, undefended);
        if (action == null || !AutoPlayLegality.isLegal(action, legalMoves)) {
            Decision fallbackDecision = heuristic(game, playerId, legalMoves, "illegal_model_action");
            return new Decision(fallbackDecision.action(), fallbackDecision.source(), fallbackDecision.reason(),
                    "model=" + answer.describe());
        }
        if (action.type() == AutoPlayAction.Type.DEFEND) {
            planCache.store(game, playerId, legalMoves, action, answer.defencePlan(undefended));
        }
        return Decision.of(action, "llm", "none");
    }

    /**
     * A lone legal option needs no model call, unless it is an optional throw-in: then "wait" is the
     * unlisted alternative (for example after the bot already passed) and the choice stays open.
     */
    private static boolean isForced(Game game, AutoPlayAction onlyOption) {
        return onlyOption.type() != AutoPlayAction.Type.ATTACK || game.getTable().isEmpty();
    }

    private Decision heuristic(Game game, String playerId, ViewerLegalMoves legalMoves, String reason) {
        return Decision.of(fallback.choose(game, playerId, legalMoves), "heuristic", reason);
    }

    private static Set<String> undefendedAttacks(Game game, ViewerLegalMoves legalMoves) {
        Set<String> attacks = new LinkedHashSet<>();
        for (AttackEntry entry : game.getTable()) {
            if (!entry.isDefended()) {
                attacks.add(entry.getAttackCard().code());
            }
        }
        attacks.addAll(legalMoves.defensesByAttackCard().keySet());
        return attacks;
    }

    /* ------------------------------------------------------------------ model call */

    /**
     * One model round trip: {@code completed} means an HTTP 2xx response arrived, and
     * {@code breakerOutcome} is what the call says about the service's health.
     */
    private record ModelCall(boolean completed, GeminiResponse response, LlmCircuitBreaker.Outcome breakerOutcome) {
    }

    private ModelCall callModel(Game game, String playerId, ViewerLegalMoves legalMoves, int optionCount) {
        String level = thinkingConfigSupported ? thinkingLevelFor(optionCount) : null;
        String body;
        try {
            body = buildRequest(game, playerId, legalMoves, level);
        } catch (IOException | RuntimeException ex) {
            log.warn("autoplay_llm_request_failed code={} player={} error={}", game.getCode(), playerId,
                    ex.getClass().getSimpleName());
            return new ModelCall(false, GeminiResponse.EMPTY, LlmCircuitBreaker.Outcome.NEUTRAL);
        }
        long startedAt = nanoClock.getAsLong();
        GeminiTransport.HttpResult result = null;
        String error = "none";
        LlmCircuitBreaker.Outcome outcome = LlmCircuitBreaker.Outcome.FAILURE;
        try {
            result = transport.post(endpoint, apiKey, body, timeout);
        } catch (HttpTimeoutException ex) {
            error = "timeout";
        } catch (IOException ex) {
            error = "io_error";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            error = "interrupted";
            outcome = LlmCircuitBreaker.Outcome.NEUTRAL;
        } catch (RuntimeException ex) {
            error = "exception";
            outcome = LlmCircuitBreaker.Outcome.NEUTRAL;
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - startedAt);
        GeminiResponse response = result == null ? GeminiResponse.EMPTY : GeminiResponse.parse(objectMapper, result.body());
        if (result != null) {
            int status = result.statusCode();
            boolean transientStatus = status == 429 || status >= 500;
            outcome = transientStatus ? LlmCircuitBreaker.Outcome.FAILURE : LlmCircuitBreaker.Outcome.SUCCESS;
            if (!result.successful()) {
                error = "http_status";
            }
        }
        logCall(game, playerId, level, latencyMs, result, response, error);
        return new ModelCall(result != null && result.successful(), response, outcome);
    }

    /** Package-private for tests. */
    LlmCircuitBreaker.State circuitState() {
        return circuitBreaker.state();
    }

    /** Decisions with at most two legal options (one card vs pass, one defence vs take) think less. */
    private String thinkingLevelFor(int optionCount) {
        return optionCount <= 2 && simpleThinkingLevel != null ? simpleThinkingLevel : thinkingLevel;
    }

    /** Package-private for request-contract tests. */
    URI endpoint() {
        return endpoint;
    }

    /** Package-private for tests: the model id actually sent (without any {@code models/} prefix). */
    String modelId() {
        return model.id();
    }

    private String buildRequest(Game game, String playerId, ViewerLegalMoves legalMoves, String level)
            throws IOException {
        String prompt = buildPrompt(game, playerId, legalMoves);
        Map<String, Object> body = new LinkedHashMap<>();
        if (systemInstructionSupported) {
            body.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", systemInstruction()))
            ));
            body.put("contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", prompt))
            )));
        } else {
            body.put("contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", systemInstruction() + "\n\n" + prompt))
            )));
        }
        body.put("generationConfig", generationConfig(level));
        return objectMapper.writeValueAsString(body);
    }

    /** Package-private for request-contract tests: the configuration sent for complex decisions. */
    Map<String, Object> generationConfig() {
        return generationConfig(thinkingConfigSupported ? thinkingLevel : null);
    }

    /** Package-private for request-contract tests: the configuration for decisions with few options. */
    Map<String, Object> generationConfigForOptions(int optionCount) {
        return generationConfig(thinkingConfigSupported ? thinkingLevelFor(optionCount) : null);
    }

    private Map<String, Object> generationConfig(String level) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        /* Gemini 3+ keeps its default sampling; only pre-Gemini-3 models get a pinned temperature. */
        if (model.pinZeroTemperature()) {
            generationConfig.put("temperature", 0);
        }
        if (jsonModeSupported) {
            generationConfig.put("responseMimeType", "application/json");
        }
        if (level != null) {
            generationConfig.put("thinkingConfig", Map.of("thinkingLevel", level));
        }
        return generationConfig;
    }

    private String buildPrompt(Game game, String playerId, ViewerLegalMoves legalMoves) throws IOException {
        Player me = game.getPlayers().stream()
                .filter(p -> Objects.equals(p.getId(), playerId))
                .findFirst()
                .orElse(null);
        List<String> hand = me == null ? List.of() : me.getHand().stream().map(c -> c.code()).toList();
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            Map<String, Object> visiblePlayer = new LinkedHashMap<>();
            visiblePlayer.put("id", player.getId());
            visiblePlayer.put("name", player.getName());
            visiblePlayer.put("bot", player.isBot());
            visiblePlayer.put("team", player.getTeam());
            visiblePlayer.put("handSize", player.handSize());
            visiblePlayer.put("self", Objects.equals(player.getId(), playerId));
            players.add(visiblePlayer);
        }
        List<Map<String, String>> table = new ArrayList<>();
        game.getTable().forEach(entry -> table.add(Map.of(
                "attackCard", entry.getAttackCard().code(),
                "defenseCard", entry.getDefenseCard() == null ? "" : entry.getDefenseCard().code()
        )));
        Map<String, Object> gameState = new LinkedHashMap<>();
        gameState.put("playerId", playerId);
        gameState.put("ownHand", hand);
        gameState.put("playersInSeatOrder", players);
        gameState.put("playerCount", game.getPlayers().size());
        gameState.put("attackerPlayerId", game.getAttackerPlayerId());
        gameState.put("defenderPlayerId", game.getDefenderPlayerId());
        gameState.put("trumpSuit", game.getTrumpSuit() == null ? null : game.getTrumpSuit().code());
        gameState.put("trumpCard", game.getTrumpCard() == null ? null : game.getTrumpCard().code());
        gameState.put("onlyTrumpCardLeftInTalon", game.getTalonSize() == 1);
        gameState.put("takingCardsInProgress", game.isTakingCardsInProgress());
        gameState.put("takeLimit", game.getTakeLimit());
        gameState.put("table", table);
        if (publicCardMemoryEnabled) {
            gameState.put("publicCardMemory", publicCardMemory(game));
        }
        gameState.put("legalMoves", legalMoves);
        String reasoningBudgetInstruction = reasoningBudgetInstruction();
        return """
                Choose the next move for playerId using the game state below.
                You must choose only from legalMoves.
                The game state contains only information visible to this bot as a human player:
                its own hand, seat order, public hand sizes, trump, whether only the visible trump card remains in the talon, table cards, current roles, and legal moves.
                Do not assume hidden opponent hands or hidden talon cards.
                %s
                Return exactly one JSON object and no extra text.
                JSON schema:
                {"strategy":"reasoning based on team/FFA and cards remaining","defensePlan":"optional plan for all undefended attacks","action":"Attack|Beat|Transfer|Pass|Take","cards":["6S","10D"],"type":"ATTACK|DEFEND|TRANSFER|TAKE|END_ROUND","cardCode":"optional","attackCardCode":"optional"}
                The cards field must describe only this response's single machine action. Put multi-card defense plans only in defensePlan, not cards.

                Mapping from strategy terms to JSON:
                - Attack -> ATTACK with cardCode
                - Beat -> DEFEND with attackCardCode and cardCode
                - Transfer -> TRANSFER with cardCode
                - Take -> TAKE with cards=[]
                - Pass -> END_ROUND only when canEndRound is true
                If choosing TAKE, do not reveal cards you could have used to defend in cards, defensePlan, or strategy.

                Role discipline:
                - If canAttack is false, do not return ATTACK.
                - If you are the defender and play a same-rank card from transferableCardCodes, that is TRANSFER, not ATTACK.
                - If you are the defender and play a card from defensesByAttackCard, that is DEFEND with the matching attackCardCode.

                Attack pacing:
                - Unless takingCardsInProgress is true, choose exactly one ATTACK card per response.
                - For normal attacks, set cards to a single-card list matching cardCode.
                - Playing one attack card at a time lets you observe the defender's response and improve the next decision.
                - When takingCardsInProgress is true, you may plan multiple final throw-ins in strategy, but still return only the next single ATTACK card in cardCode.

                If defending and multiple attack cards are undefended, reason holistically:
                - First determine whether all undefended attacks can be beaten with the available defense options.
                - Prefer a defense assignment that preserves trumps, aces, and flexible cards for later attacks.
                - Choose this response's single DEFEND action as the next step from that full defense plan.
                - Include the full plan in defensePlan or strategy.
                - If the whole set cannot be defended, choose TAKE instead of wasting a good card on a partial defense.
                - You may still choose TAKE for strategic reasons even if defense is possible, but then reveal no defense cards.

                Use human-like card-counting in strategy:
                - When publicCardMemory is present, use it as public table history available to all players.
                - discardedOutOfPlay cards cannot appear again and cannot be used by any player.
                - knownCardsByPlayer lists cards that were publicly picked up from the table and are known to be in that player's hand unless they have since been played.
                - Use known opponent cards to anticipate defenses, future transfers, and throw-in ranks.
                - In strategy, briefly refer to the public-card memory or card-counting inference that influenced the move.

                Populate strategy/action/cards for debug logging, but type/cardCode/attackCardCode are the authoritative machine fields.

                Current game state:
                %s
                """.formatted(reasoningBudgetInstruction, objectMapper.writeValueAsString(gameState));
    }

    private String reasoningBudgetInstruction() {
        if (!promptReasoningBudgetUsed) {
            return "";
        }
        return "Budgeted reasoning: reason for no more than " + reasoningBudgetSeconds
                + " seconds. If still uncertain, stop reasoning and return the best legal move immediately."
                + " Do not spend extra time seeking a perfect move.";
    }

    private Map<String, Object> publicCardMemory(Game game) {
        List<Map<String, Object>> knownCardsByPlayer = new ArrayList<>();
        game.getKnownCardsByPlayer().forEach((knownPlayerId, cards) -> {
            Map<String, Object> known = new LinkedHashMap<>();
            known.put("playerId", knownPlayerId);
            known.put("playerName", game.getPlayers().stream()
                    .filter(player -> Objects.equals(player.getId(), knownPlayerId))
                    .map(Player::getName)
                    .findFirst()
                    .orElse("?"));
            known.put("cards", cards.stream().map(Card::code).toList());
            knownCardsByPlayer.add(known);
        });
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("discardedOutOfPlay", game.getDiscardedCards().stream().map(Card::code).toList());
        memory.put("knownCardsByPlayer", knownCardsByPlayer);
        return memory;
    }

    private String systemInstruction() {
        return """
                Role: You are a master Durak strategist. You play with precision and aggressive card-counting.

                Card counting: Infer from visible information only. Track your own hand, the visible trump card, cards currently on the table, and any public played/discarded cards if provided. Use those observations to estimate suit pressure, remaining trump risk, and which ranks are safe to throw in. Never assume hidden opponent hands or hidden talon cards.

                1. Game Flow & Direction

                Direction: Counter-Clockwise (to your right).

                The Bout: The player to the right of the attacker is the defender.

                Throw-ins: Once the primary attacker is finished, the right to "throw in" additional cards passes to the player to the right of the defender.

                2. Multi-Mode Logic (Teams vs. FFA)

                IF 4 Players (Team Play): You and the player sitting opposite you are partners.

                STRICT RULE: You are strictly forbidden from attacking your partner. Even if your partner has decided to "Take" (Беру), you may not throw in cards to their hand. You only attack the two opponents.

                IF 2 or 3 Players (all against all): Every other player is an enemy. Your only goal is to empty your hand first.

                3. Advanced Gameplay Mechanics

                Perevodnoy (Transferable):

                As a defender, you can play a card of the same rank as the attack to transfer the bout to the player on your right.

                Constraint: You cannot transfer if the next defender has fewer cards in their hand than the total cards currently on the table.

                Podkidnoy (Throw-in) & The "Take" Window:

                If a defender says "Take" (Беру), the table remains open. Other valid attackers may continue to "throw in" matching ranks until the limit is reached (6 cards or the defender's hand size).

                The defender must pick up every card played during the window.

                4. Decision Priorities

                Safety First: Protect high trumps and Aces for the end-game.

                Partner Awareness (4-player): Watch your partner's hand size. If they are low, play aggressively against the opponent to your right to ensure your partner gets to shed their last cards.

                5. Public Card Memory

                Use publicCardMemory when it is provided. Cards discarded after a defended bout are out of play and cannot be played again. Cards picked up by a player are publicly known to be in that player's hand until they are later played. Reason about these known cards when choosing attacks, defenses, transfers, and throw-ins. Refer to this card-counting memory in your strategy explanation when it affects the move, but never invent card history that is not visible or otherwise provided.

                6. Attack Pacing

                Prefer one-card attacks. Outside of the final throw-in window after a defender takes, return one ATTACK card at a time so you can observe whether the defender beats, transfers, or takes before choosing the next attack. During the take window, you may plan several throw-ins, but the machine action must still be the next single card.
                """;
    }

    /* ------------------------------------------------------------------ logging */

    private void logCall(Game game, String playerId, String level, long latencyMs, GeminiTransport.HttpResult result,
                         GeminiResponse response, String error) {
        GeminiResponse.Usage usage = response.usage();
        String errorDetail = "";
        if (result != null && !result.successful()) {
            errorDetail = " errorStatus=" + orDash(response.errorStatus())
                    + " errorMessage=\"" + sanitize(response.errorMessage()) + "\"";
        }
        log.info("autoplay_llm_call code={} player={} model={} thinkingLevel={} latencyMs={} httpStatus={} "
                        + "promptTokens={} cachedTokens={} outputTokens={} thoughtTokens={} totalTokens={} "
                        + "finishReason={} error={}{}",
                game.getCode(), playerId, model.id(), level == null ? "none" : level, latencyMs,
                result == null ? 0 : result.statusCode(), usage.promptTokens(), usage.cachedTokens(),
                usage.outputTokens(), usage.thoughtTokens(), usage.totalTokens(),
                orDash(response.finishReason()), error, errorDetail);
    }

    private void logStrategy(Game game, String playerId, GeminiAnswer answer, GeminiResponse response) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("autoplay_llm_strategy code={} player={} strategy=\"{}\" thoughts=\"{}\"",
                game.getCode(), playerId, sanitize(answer.strategy()), sanitize(String.join(" ", response.thoughts())));
    }

    /** One line, bounded, quote-safe, and never containing the API key. */
    private String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text;
        if (!apiKey.isBlank()) {
            cleaned = cleaned.replace(apiKey, "[redacted]");
        }
        cleaned = cleaned.replaceAll("[\\r\\n\\t]+", " ").replace('"', '\'').trim();
        return cleaned.length() > LOG_TEXT_LIMIT ? cleaned.substring(0, LOG_TEXT_LIMIT) + "..." : cleaned;
    }

    private static String actionType(AutoPlayAction action) {
        return action == null ? "none" : action.type().name();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
