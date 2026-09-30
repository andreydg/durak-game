package com.example.durakgame.service.autoplay;

/**
 * Configuration of {@link GeminiAutoPlayDecisionEngine}; defaults mirror {@code application.properties}.
 * Capability flags accept {@code auto} (derive from the model version), {@code true} or {@code false}.
 */
record GeminiSettings(
        boolean enabled,
        String apiKey,
        String model,
        String baseUrl,
        String thinkingLevel,
        String simpleThinkingLevel,
        boolean publicCardMemoryEnabled,
        long reasoningBudgetSeconds,
        String jsonModeFlag,
        String systemInstructionFlag,
        String thinkingConfigFlag,
        String promptReasoningBudgetFlag,
        long requestTimeoutMs
) {
    static final String DEFAULT_MODEL = "gemini-3.8-flash";
    static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    static final String DEFAULT_THINKING_LEVEL = "HIGH";
    static final String DEFAULT_SIMPLE_THINKING_LEVEL = "LOW";

    static Builder builder() {
        return new Builder();
    }

    /** Mutable builder so tests and wiring can override only what they care about. */
    static final class Builder {
        private boolean enabled = true;
        private String apiKey = "";
        private String model = DEFAULT_MODEL;
        private String baseUrl = DEFAULT_BASE_URL;
        private String thinkingLevel = DEFAULT_THINKING_LEVEL;
        private String simpleThinkingLevel = DEFAULT_SIMPLE_THINKING_LEVEL;
        private boolean publicCardMemoryEnabled = true;
        private long reasoningBudgetSeconds = 30;
        private String jsonModeFlag = "auto";
        private String systemInstructionFlag = "auto";
        private String thinkingConfigFlag = "auto";
        private String promptReasoningBudgetFlag = "auto";
        private long requestTimeoutMs = 30_000;

        Builder enabled(boolean value) {
            enabled = value;
            return this;
        }

        Builder apiKey(String value) {
            apiKey = value;
            return this;
        }

        Builder model(String value) {
            model = value;
            return this;
        }

        Builder baseUrl(String value) {
            baseUrl = value;
            return this;
        }

        Builder thinkingLevel(String value) {
            thinkingLevel = value;
            return this;
        }

        Builder simpleThinkingLevel(String value) {
            simpleThinkingLevel = value;
            return this;
        }

        Builder publicCardMemoryEnabled(boolean value) {
            publicCardMemoryEnabled = value;
            return this;
        }

        Builder reasoningBudgetSeconds(long value) {
            reasoningBudgetSeconds = value;
            return this;
        }

        Builder jsonModeFlag(String value) {
            jsonModeFlag = value;
            return this;
        }

        Builder systemInstructionFlag(String value) {
            systemInstructionFlag = value;
            return this;
        }

        Builder thinkingConfigFlag(String value) {
            thinkingConfigFlag = value;
            return this;
        }

        Builder promptReasoningBudgetFlag(String value) {
            promptReasoningBudgetFlag = value;
            return this;
        }

        Builder requestTimeoutMs(long value) {
            requestTimeoutMs = value;
            return this;
        }

        GeminiSettings build() {
            return new GeminiSettings(enabled, apiKey, model, baseUrl, thinkingLevel, simpleThinkingLevel,
                    publicCardMemoryEnabled, reasoningBudgetSeconds, jsonModeFlag, systemInstructionFlag,
                    thinkingConfigFlag, promptReasoningBudgetFlag, requestTimeoutMs);
        }
    }
}
