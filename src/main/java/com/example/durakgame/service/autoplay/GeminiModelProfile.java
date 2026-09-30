package com.example.durakgame.service.autoplay;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the auto-play engine knows about the configured model, parsed from its id instead of
 * matching fragile name prefixes. {@code models/gemini-3.8-flash} and {@code gemini-3.8-flash}
 * are the same model; the version drives which request features are sent by default.
 *
 * @param id     model id without the optional {@code models/} resource prefix, as used in the URL
 * @param family model family
 * @param major  major version, or -1 when the id carries none (for example an alias such as
 *               {@code gemini-flash-latest})
 * @param minor  minor version, 0 when absent
 */
record GeminiModelProfile(String id, Family family, int major, int minor) {
    enum Family {
        GEMINI,
        GEMMA,
        OTHER
    }

    private static final String RESOURCE_PREFIX = "models/";
    private static final Pattern VERSION = Pattern.compile("^(?:gemini|gemma)-(\\d+)(?:\\.(\\d+))?",
            Pattern.CASE_INSENSITIVE);

    static GeminiModelProfile parse(String configuredModel, String defaultModel) {
        String id = configuredModel == null ? "" : configuredModel.trim();
        if (id.regionMatches(true, 0, RESOURCE_PREFIX, 0, RESOURCE_PREFIX.length())) {
            id = id.substring(RESOURCE_PREFIX.length()).trim();
        }
        if (id.isEmpty()) {
            id = defaultModel;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        Family family = lower.startsWith("gemini") ? Family.GEMINI
                : lower.startsWith("gemma") ? Family.GEMMA
                : Family.OTHER;
        int major = -1;
        int minor = 0;
        Matcher matcher = VERSION.matcher(id);
        if (family != Family.OTHER && matcher.find()) {
            major = Integer.parseInt(matcher.group(1));
            minor = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        }
        return new GeminiModelProfile(id, family, major, minor);
    }

    boolean versionKnown() {
        return major >= 0;
    }

    /** Gemini 3 and newer: thinking levels, JSON mode, system instructions, default sampling. */
    boolean geminiThreeOrNewer() {
        return family == Family.GEMINI && major >= 3;
    }

    /** Gemma 3 (including 3n) and older accept neither JSON mode nor system instructions. */
    boolean legacyGemma() {
        return family == Family.GEMMA && versionKnown() && major <= 3;
    }

    boolean defaultJsonMode() {
        return !legacyGemma();
    }

    boolean defaultSystemInstruction() {
        return !legacyGemma();
    }

    boolean defaultThinkingConfig() {
        return geminiThreeOrNewer();
    }

    boolean defaultPromptReasoningBudget() {
        return family == Family.GEMMA;
    }

    /**
     * Pins temperature 0 only for models that predate Gemini 3 (Gemini 1.x/2.x and Gemma). Gemini 3
     * guidance is to keep the default temperature; unversioned aliases are treated the same way.
     */
    boolean pinZeroTemperature() {
        return family == Family.GEMMA || (family == Family.GEMINI && versionKnown() && major < 3);
    }

    /** Compact description for logs, e.g. {@code gemini-3.8}. */
    String describe() {
        String name = family.name().toLowerCase(Locale.ROOT);
        return versionKnown() ? name + "-" + major + "." + minor : name + "-unversioned";
    }
}
