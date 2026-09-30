package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.AttackEntry;
import com.example.durakgame.model.Game;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final boolean jsonModeSupported;
    private final boolean systemInstructionSupported;
    private final boolean thinkingConfigSupported;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmCallBudget callBudget;
    private final DefencePlanCache planCache;
    private final GeminiPrompt prompt;

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
        /* Model capabilities: derived from the model version, overridable per property (auto|true|false). */
        this.jsonModeSupported = resolveCapability(settings.jsonModeFlag(), model.defaultJsonMode());
        this.systemInstructionSupported = resolveCapability(settings.systemInstructionFlag(),
                model.defaultSystemInstruction());
        this.thinkingConfigSupported = resolveCapability(settings.thinkingConfigFlag(), model.defaultThinkingConfig());
        boolean promptReasoningBudgetUsed = resolveCapability(settings.promptReasoningBudgetFlag(),
                model.defaultPromptReasoningBudget());
        this.circuitBreaker = new LlmCircuitBreaker(settings.circuitBreakerFailureThreshold(),
                Duration.ofMillis(Math.max(0, settings.circuitBreakerCooldownMs())), nanoClock);
        this.callBudget = new LlmCallBudget(settings.maxCallsPerMinute(), nanoClock);
        this.planCache = new DefencePlanCache(DefencePlanCache.DEFAULT_MAX_PLANS, DefencePlanCache.DEFAULT_TTL,
                nanoClock);
        this.prompt = new GeminiPrompt(objectMapper, settings.publicCardMemoryEnabled(),
                promptReasoningBudgetUsed ? reasoningBudgetInstruction(settings.reasoningBudgetSeconds()) : "");
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

    /**
     * The request body. The system instruction and the static part of the user message come first and
     * never change between turns, so Gemini's implicit caching can reuse them; per-turn state is last.
     * Package-private for request-contract tests.
     */
    String buildRequest(Game game, String playerId, ViewerLegalMoves legalMoves, String level) throws IOException {
        String userPrompt = prompt.userPrompt(game, playerId, legalMoves);
        Map<String, Object> body = new LinkedHashMap<>();
        if (systemInstructionSupported) {
            body.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", prompt.systemInstruction()))
            ));
            body.put("contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", userPrompt))
            )));
        } else {
            body.put("contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", prompt.systemInstruction() + "\n\n" + userPrompt))
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

    private static String reasoningBudgetInstruction(long reasoningBudgetSeconds) {
        return "Budgeted reasoning: reason for no more than " + Math.max(1L, reasoningBudgetSeconds)
                + " seconds. If still uncertain, stop reasoning and return the best legal move immediately."
                + " Do not spend extra time seeking a perfect move.";
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
        cleaned = cleaned.replaceAll("\\p{Cntrl}+", " ").replace('"', '\'').trim();
        return cleaned.length() > LOG_TEXT_LIMIT ? cleaned.substring(0, LOG_TEXT_LIMIT) + "..." : cleaned;
    }

    private static String actionType(AutoPlayAction action) {
        return action == null ? "none" : action.type().name();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
