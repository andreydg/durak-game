package com.example.durakgame.service.autoplay;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.Rank;
import com.example.durakgame.model.Suit;
import com.example.durakgame.model.ViewerLegalMoves;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Chooses bot moves with Claude on Anthropic's own Messages API ({@code autoplay.provider=anthropic})
 * and falls back to {@link HeuristicAutoPlayDecisionEngine}; the decision flow is
 * {@link LlmAutoPlayDecisionEngine}'s. Calls authenticate as the service's Cloud Run identity
 * through {@link AnthropicFederation}: there is no API key.
 *
 * <p>The request is {@link MovePrompt}'s, as Gemini gets it: the rules as {@code system}, then one
 * user message of two text blocks, the constant instructions (the cache breakpoint, so every turn
 * reads the rules and instructions from Anthropic's prompt cache) and the per-turn state. The answer
 * is constrained by a JSON schema ({@code output_config.format}). It sends the effort explicitly,
 * lighter for decisions with at most two options, and no {@code thinking} field, so thinking stays
 * adaptive; it sends no temperature, tools or fallbacks: a refusal falls back to the heuristic.
 *
 * <p>A throttle (429), an outage (5xx, including 529 overloaded), a refused credential (401/403), a
 * failed token exchange, a timeout or an I/O error counts against the circuit breaker. Any other
 * status is our own request's fault and does not. Each call logs one single-line
 * {@code autoplay_llm_call} entry with the token usage, cache reads and writes included, and
 * Anthropic's request id; never a token.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "autoplay", name = "provider", havingValue = "anthropic")
public class ClaudeAutoPlayDecisionEngine extends LlmAutoPlayDecisionEngine {
    private static final long MAX_CONNECT_TIMEOUT_MS = 5000;
    private static final int LOG_TOKEN_LIMIT = 64;

    private final ObjectMapper objectMapper;
    private final AnthropicMessagesTransport transport;
    private final LongSupplier nanoClock;
    private final boolean enabled;
    private final boolean federationConfigured;
    private final String model;
    private final String effort;
    private final String simpleEffort;
    private final int maxTokens;
    private final String inferenceGeo;
    private final Duration timeout;
    private final ObjectNode answerSchema;
    private final MovePrompt prompt;

    @Autowired
    public ClaudeAutoPlayDecisionEngine(
            HeuristicAutoPlayDecisionEngine fallback,
            ObjectMapper objectMapper,
            AnthropicFederation federation,
            @Value("${autoplay.anthropic.enabled:true}") boolean enabled,
            @Value("${autoplay.anthropic.model:" + ClaudeSettings.DEFAULT_MODEL + "}") String model,
            @Value("${autoplay.anthropic.effort:" + ClaudeSettings.DEFAULT_EFFORT + "}") String effort,
            @Value("${autoplay.anthropic.simple-effort:" + ClaudeSettings.DEFAULT_SIMPLE_EFFORT + "}")
            String simpleEffort,
            @Value("${autoplay.anthropic.max-tokens:" + ClaudeSettings.DEFAULT_MAX_TOKENS + "}") int maxTokens,
            @Value("${autoplay.anthropic.inference-geo:" + ClaudeSettings.DEFAULT_INFERENCE_GEO + "}")
            String inferenceGeo,
            @Value("${autoplay.request-timeout-ms:30000}") long timeoutMs,
            @Value("${autoplay.anthropic.circuit-breaker.failure-threshold:"
                    + ClaudeSettings.DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD + "}") int circuitBreakerFailureThreshold,
            @Value("${autoplay.anthropic.circuit-breaker.cooldown-ms:"
                    + ClaudeSettings.DEFAULT_CIRCUIT_BREAKER_COOLDOWN_MS + "}") long circuitBreakerCooldownMs,
            @Value("${autoplay.anthropic.max-calls-per-minute:" + ClaudeSettings.DEFAULT_MAX_CALLS_PER_MINUTE + "}")
            int maxCallsPerMinute
    ) {
        this(fallback, objectMapper, new ClaudeSettings(enabled, model, effort, simpleEffort, maxTokens, inferenceGeo,
                        timeoutMs, circuitBreakerFailureThreshold, circuitBreakerCooldownMs, maxCallsPerMinute),
                federation.configured(),
                AnthropicMessagesTransport.federated(federation, AnthropicMessagesTransport.MESSAGES,
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofMillis(Math.max(1, Math.min(MAX_CONNECT_TIMEOUT_MS, timeoutMs))))
                                .build(),
                        System::nanoTime),
                System::nanoTime);
    }

    /**
     * Package-private for tests: scripted transport and a controllable clock.
     *
     * @throws IllegalStateException when a setting is not one the API accepts, so a bad value stops
     *                               the service from starting instead of failing every call
     */
    ClaudeAutoPlayDecisionEngine(
            HeuristicAutoPlayDecisionEngine fallback,
            ObjectMapper objectMapper,
            ClaudeSettings settings,
            boolean federationConfigured,
            AnthropicMessagesTransport transport,
            LongSupplier nanoClock
    ) {
        super(fallback, nanoClock, settings.circuitBreakerFailureThreshold(), settings.circuitBreakerCooldownMs(),
                settings.maxCallsPerMinute());
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.nanoClock = nanoClock;
        this.enabled = settings.enabled();
        this.federationConfigured = federationConfigured;
        this.model = require("autoplay.anthropic.model", settings.model());
        this.effort = oneOf("autoplay.anthropic.effort", settings.effort(), ClaudeSettings.EFFORTS);
        String simple = settings.simpleEffort() == null || settings.simpleEffort().isBlank()
                ? effort
                : settings.simpleEffort();
        this.simpleEffort = oneOf("autoplay.anthropic.simple-effort", simple, ClaudeSettings.EFFORTS);
        if (settings.maxTokens() < 1) {
            throw new IllegalStateException("autoplay.anthropic.max-tokens must be positive");
        }
        this.maxTokens = settings.maxTokens();
        this.inferenceGeo = settings.inferenceGeo() == null || settings.inferenceGeo().isBlank()
                ? null
                : oneOf("autoplay.anthropic.inference-geo", settings.inferenceGeo(), ClaudeSettings.INFERENCE_GEOS);
        this.timeout = Duration.ofMillis(Math.max(1, settings.requestTimeoutMs()));
        this.answerSchema = answerSchema(objectMapper);
        this.prompt = new MovePrompt(objectMapper, true, "");
        String format = "autoplay_claude_config enabled={} federationConfigured={} model={} effort={} simpleEffort={} "
                + "maxTokens={} inferenceGeo={} timeoutMs={} circuitBreakerFailureThreshold={} "
                + "circuitBreakerCooldownMs={} maxCallsPerMinute={} callBurst={}";
        Object[] fields = {enabled, federationConfigured, model, effort, simpleEffort, maxTokens,
                inferenceGeo == null ? "default" : inferenceGeo, timeout.toMillis(),
                settings.circuitBreakerFailureThreshold(), settings.circuitBreakerCooldownMs(),
                settings.maxCallsPerMinute(), callBurst()};
        if (enabled && !federationConfigured) {
            /* Without the federation ids every bot plays the heuristic; say so where it is noticed. */
            log.warn(format, fields);
        } else {
            log.info(format, fields);
        }
    }

    private static String require(String property, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
        return value.strip();
    }

    private static String oneOf(String property, String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalStateException(property + " must be one of " + allowed.stream().sorted().toList()
                    + ", not \"" + value + "\"");
        }
        return normalized;
    }

    @Override
    protected boolean configured() {
        return enabled && federationConfigured;
    }

    /* ------------------------------------------------------------------ model call */

    @Override
    protected ModelCall callModel(Game game, String playerId, ViewerLegalMoves legalMoves, int optionCount) {
        String level = effortFor(optionCount);
        String body;
        try {
            body = buildRequest(game, playerId, legalMoves, level);
        } catch (IOException | RuntimeException ex) {
            log.warn("autoplay_llm_request_failed code={} player={} error={}", game.getCode(), playerId,
                    ex.getClass().getSimpleName());
            return ModelCall.failed(LlmCircuitBreaker.Outcome.NEUTRAL);
        }
        long startedAt = nanoClock.getAsLong();
        AnthropicMessagesTransport.HttpResult result = null;
        String error = "none";
        LlmCircuitBreaker.Outcome outcome = LlmCircuitBreaker.Outcome.FAILURE;
        try {
            result = transport.post(body, timeout);
        } catch (AnthropicFederation.Unavailable ex) {
            error = "federation_" + ex.failure().name().toLowerCase(Locale.ROOT);
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
        ClaudeResponse response = result == null ? ClaudeResponse.EMPTY : ClaudeResponse.parse(result.body());
        if (result != null) {
            int status = result.statusCode();
            boolean unhealthy = status == 401 || status == 403 || status == 429 || status >= 500;
            outcome = unhealthy ? LlmCircuitBreaker.Outcome.FAILURE : LlmCircuitBreaker.Outcome.SUCCESS;
            if (!result.successful()) {
                error = "http_status";
            }
        }
        logCall(game, playerId, level, latencyMs, result, response, error);
        if (result == null || !result.successful()) {
            return ModelCall.failed(outcome);
        }
        String stopReason = response.stopReason() == null ? "" : response.stopReason();
        return switch (stopReason) {
            case "end_turn" -> ModelCall.answered(outcome, response.answer(), List.of());
            /* A safety classifier declined, or the cap cut the answer off: either way there is none. */
            case "refusal", "max_tokens" -> ModelCall.unanswered(outcome, stopReason);
            default -> ModelCall.unanswered(outcome, "unparseable");
        };
    }

    /** Decisions with at most two legal options (one card vs pass, one defence vs take) think less. */
    String effortFor(int optionCount) {
        return optionCount <= 2 ? simpleEffort : effort;
    }

    /** Package-private for tests: the model id actually sent. */
    String modelId() {
        return model;
    }

    /**
     * The request body. {@code system} and the first user block never change between turns, and the
     * cache breakpoint closes that prefix; the per-turn state comes last. Package-private for
     * request-contract tests.
     */
    String buildRequest(Game game, String playerId, ViewerLegalMoves legalMoves, String level) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", prompt.systemInstruction());
        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        ArrayNode content = message.putArray("content");
        ObjectNode instructions = content.addObject();
        instructions.put("type", "text");
        instructions.put("text", prompt.userPrefix());
        instructions.putObject("cache_control").put("type", "ephemeral");
        ObjectNode state = content.addObject();
        state.put("type", "text");
        state.put("text", objectMapper.writeValueAsString(prompt.gameState(game, playerId, legalMoves)));
        ObjectNode outputConfig = body.putObject("output_config");
        outputConfig.put("effort", level);
        ObjectNode format = outputConfig.putObject("format");
        format.put("type", "json_schema");
        format.set("schema", answerSchema);
        if (inferenceGeo != null) {
            body.put("inference_geo", inferenceGeo);
        }
        return objectMapper.writeValueAsString(body);
    }

    /**
     * {@link MovePrompt}'s answer object as a schema Claude's structured outputs enforce: every object
     * closed, card codes from the 36-card deck, nullable cards as {@code anyOf} with {@code null}. The
     * legality checks still run on the answer.
     */
    static ObjectNode answerSchema(ObjectMapper mapper) {
        ArrayNode cards = mapper.createArrayNode();
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                cards.add(rank.code() + suit.code());
            }
        }
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("strategy").put("type", "string");
        ObjectNode type = properties.putObject("type");
        type.put("type", "string");
        ArrayNode types = type.putArray("enum");
        for (AutoPlayAction.Type value : AutoPlayAction.Type.values()) {
            types.add(value.name());
        }
        properties.set("cardCode", nullable(card(mapper, cards), mapper));
        properties.set("attackCardCode", nullable(card(mapper, cards), mapper));
        ObjectNode plan = properties.putObject("defensePlan");
        plan.put("type", "array");
        ObjectNode pair = plan.putObject("items");
        pair.put("type", "object");
        ObjectNode pairProperties = pair.putObject("properties");
        pairProperties.set("attackCardCode", card(mapper, cards));
        pairProperties.set("cardCode", card(mapper, cards));
        pair.putArray("required").add("attackCardCode").add("cardCode");
        pair.put("additionalProperties", false);
        schema.putArray("required").add("strategy").add("type").add("cardCode").add("attackCardCode")
                .add("defensePlan");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode card(ObjectMapper mapper, ArrayNode cards) {
        ObjectNode card = mapper.createObjectNode();
        card.put("type", "string");
        card.set("enum", cards.deepCopy());
        return card;
    }

    private static ObjectNode nullable(ObjectNode schema, ObjectMapper mapper) {
        ObjectNode nullable = mapper.createObjectNode();
        ArrayNode anyOf = nullable.putArray("anyOf");
        anyOf.add(schema);
        anyOf.addObject().put("type", "null");
        return nullable;
    }

    /* ------------------------------------------------------------------ logging */

    private void logCall(Game game, String playerId, String level, long latencyMs,
                         AnthropicMessagesTransport.HttpResult result, ClaudeResponse response, String error) {
        ClaudeResponse.Usage usage = response.usage();
        StringBuilder detail = new StringBuilder();
        if (result != null && !result.successful()) {
            detail.append(" errorType=").append(orDash(logToken(response.errorType())))
                    .append(" errorMessage=\"").append(sanitize(response.errorMessage())).append('"');
        }
        if ("refusal".equals(response.stopReason())) {
            detail.append(" stopCategory=").append(orDash(logToken(response.stopCategory())));
        }
        log.info("autoplay_llm_call code={} player={} model={} effort={} latencyMs={} httpStatus={} "
                        + "promptTokens={} cachedTokens={} cacheWriteTokens={} outputTokens={} finishReason={} "
                        + "requestId={} error={}{}",
                game.getCode(), playerId, model, level, latencyMs, result == null ? 0 : result.statusCode(),
                usage.promptTokens(), usage.cacheReadTokens(), usage.cacheWriteTokens(), usage.outputTokens(),
                orDash(logToken(response.stopReason())), orDash(logToken(result == null ? null : result.requestId())),
                error, detail);
    }

    /** A provider-supplied identifier reduced to one safe, bounded log token. */
    private static String logToken(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_.-]", "");
        return cleaned.length() > LOG_TOKEN_LIMIT ? cleaned.substring(0, LOG_TOKEN_LIMIT) : cleaned;
    }
}
