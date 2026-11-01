package com.example.durakgame.service.autoplay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Refuses to start with an {@code autoplay.provider} that no engine answers to. The model engines
 * are conditional on that property, so a typo would otherwise leave every bot on the heuristic
 * without a word; a revision that fails to start keeps the previous one serving instead.
 */
@Component
class AutoPlayProviderCheck {
    /** {@link GeminiAutoPlayDecisionEngine} and {@link ClaudeAutoPlayDecisionEngine}. */
    static final Set<String> PROVIDERS = Set.of("gemini", "anthropic");

    AutoPlayProviderCheck(@Value("${autoplay.provider:gemini}") String provider) {
        /* Spring matches the engines' havingValue ignoring case, but not surrounding spaces. */
        if (provider == null || !PROVIDERS.contains(provider.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("autoplay.provider must be one of " + PROVIDERS.stream().sorted().toList()
                    + ", not \"" + provider + "\"");
        }
    }
}
