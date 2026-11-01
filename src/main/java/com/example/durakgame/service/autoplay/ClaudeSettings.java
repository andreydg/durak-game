package com.example.durakgame.service.autoplay;

import java.util.Set;

/**
 * Configuration of {@link ClaudeAutoPlayDecisionEngine}; defaults mirror {@code application.properties}.
 * The federation ids live in {@link AnthropicFederation}.
 */
record ClaudeSettings(
        boolean enabled,
        String model,
        String effort,
        String simpleEffort,
        int maxTokens,
        String inferenceGeo,
        long requestTimeoutMs,
        int circuitBreakerFailureThreshold,
        long circuitBreakerCooldownMs,
        int maxCallsPerMinute
) {
    static final String DEFAULT_MODEL = "claude-opus-5-5";
    /** Claude Opus 5.5's own default; sent explicitly so the request says what it gets. */
    static final String DEFAULT_EFFORT = "medium";
    static final String DEFAULT_SIMPLE_EFFORT = "low";
    /** Adaptive thinking counts against this cap too; the answer itself is about 50 tokens. */
    static final int DEFAULT_MAX_TOKENS = 8192;
    static final String DEFAULT_INFERENCE_GEO = "global";
    static final int DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD = 3;
    static final long DEFAULT_CIRCUIT_BREAKER_COOLDOWN_MS = 60_000;
    static final int DEFAULT_MAX_CALLS_PER_MINUTE = 120;

    static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");
    /** {@code us} keeps inference in the United States at 1.1x list price; {@code global} is list price. */
    static final Set<String> INFERENCE_GEOS = Set.of("us", "global");

    static Builder builder() {
        return new Builder();
    }

    /** Mutable builder so tests and wiring can override only what they care about. */
    static final class Builder {
        private boolean enabled = true;
        private String model = DEFAULT_MODEL;
        private String effort = DEFAULT_EFFORT;
        private String simpleEffort = DEFAULT_SIMPLE_EFFORT;
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private String inferenceGeo = DEFAULT_INFERENCE_GEO;
        private long requestTimeoutMs = 30_000;
        private int circuitBreakerFailureThreshold = DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD;
        private long circuitBreakerCooldownMs = DEFAULT_CIRCUIT_BREAKER_COOLDOWN_MS;
        private int maxCallsPerMinute = DEFAULT_MAX_CALLS_PER_MINUTE;

        Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        Builder model(String value) {
            model = value;
            return this;
        }

        Builder effort(String value) {
            effort = value;
            return this;
        }

        Builder simpleEffort(String value) {
            simpleEffort = value;
            return this;
        }

        Builder maxTokens(int value) {
            maxTokens = value;
            return this;
        }

        Builder inferenceGeo(String value) {
            inferenceGeo = value;
            return this;
        }

        Builder requestTimeoutMs(long value) {
            requestTimeoutMs = value;
            return this;
        }

        Builder circuitBreakerFailureThreshold(int value) {
            circuitBreakerFailureThreshold = value;
            return this;
        }

        Builder circuitBreakerCooldownMs(long value) {
            circuitBreakerCooldownMs = value;
            return this;
        }

        Builder maxCallsPerMinute(int value) {
            maxCallsPerMinute = value;
            return this;
        }

        ClaudeSettings build() {
            return new ClaudeSettings(enabled, model, effort, simpleEffort, maxTokens, inferenceGeo, requestTimeoutMs,
                    circuitBreakerFailureThreshold, circuitBreakerCooldownMs, maxCallsPerMinute);
        }
    }
}
