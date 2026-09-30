package com.example.durakgame.service.autoplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiModelProfileTest {

    private static GeminiModelProfile parse(String model) {
        return GeminiModelProfile.parse(model, GeminiSettings.DEFAULT_MODEL);
    }

    @Test
    void parsesFamilyAndVersionFromTheModelId() {
        GeminiModelProfile profile = parse("gemini-3.8-flash");

        assertEquals("gemini-3.8-flash", profile.id());
        assertEquals(GeminiModelProfile.Family.GEMINI, profile.family());
        assertEquals(3, profile.major());
        assertEquals(8, profile.minor());
    }

    @Test
    void stripsAnOptionalModelsResourcePrefix() {
        assertEquals("gemini-3.8-flash", parse("models/gemini-3.8-flash").id());
        assertEquals("gemini-2.5-pro", parse("  Models/gemini-2.5-pro ").id());
    }

    @Test
    void blankModelFallsBackToTheDefault() {
        assertEquals(GeminiSettings.DEFAULT_MODEL, parse("  ").id());
        assertEquals(GeminiSettings.DEFAULT_MODEL, parse("models/").id());
        assertEquals(GeminiSettings.DEFAULT_MODEL, parse(null).id());
    }

    @Test
    void everyGeminiThreeAndNewerModelGetsModernRequestFeatures() {
        for (String model : new String[]{"gemini-3.7-flash", "gemini-3.8-flash", "gemini-3-pro-preview",
                "gemini-3.9-flash-lite", "gemini-4.0-pro", "gemini-4-flash"}) {
            GeminiModelProfile profile = parse(model);
            assertTrue(profile.defaultThinkingConfig(), model);
            assertTrue(profile.defaultJsonMode(), model);
            assertTrue(profile.defaultSystemInstruction(), model);
            assertFalse(profile.pinZeroTemperature(), model + " must keep the default temperature");
            assertFalse(profile.defaultPromptReasoningBudget(), model);
        }
    }

    @Test
    void olderGeminiModelsKeepTheLegacyRequestShape() {
        GeminiModelProfile profile = parse("gemini-2.5-flash");

        assertEquals(2, profile.major());
        assertEquals(5, profile.minor());
        assertFalse(profile.defaultThinkingConfig());
        assertTrue(profile.defaultJsonMode());
        assertTrue(profile.pinZeroTemperature());
    }

    @Test
    void gemmaThreeModelsKeepTodaysBehaviour() {
        for (String model : new String[]{"gemma-3-27b-it", "gemma-3-4b-it", "gemma-3n-e4b-it"}) {
            GeminiModelProfile profile = parse(model);
            assertEquals(GeminiModelProfile.Family.GEMMA, profile.family(), model);
            assertFalse(profile.defaultJsonMode(), model);
            assertFalse(profile.defaultSystemInstruction(), model);
            assertFalse(profile.defaultThinkingConfig(), model);
            assertTrue(profile.defaultPromptReasoningBudget(), model);
            assertTrue(profile.pinZeroTemperature(), model);
        }
    }

    @Test
    void newerGemmaModelsAcceptJsonModeAndSystemInstructions() {
        GeminiModelProfile profile = parse("gemma-4-31b-it");

        assertTrue(profile.defaultJsonMode());
        assertTrue(profile.defaultSystemInstruction());
        assertFalse(profile.defaultThinkingConfig());
        assertTrue(profile.defaultPromptReasoningBudget());
    }

    @Test
    void unversionedAliasesUseSafeDefaults() {
        GeminiModelProfile profile = parse("gemini-flash-latest");

        assertEquals(GeminiModelProfile.Family.GEMINI, profile.family());
        assertFalse(profile.versionKnown());
        assertFalse(profile.defaultThinkingConfig(), "thinking levels are only sent to known Gemini 3+ models");
        assertTrue(profile.defaultJsonMode());
        assertFalse(profile.pinZeroTemperature(), "aliases track current models, which keep default sampling");
        assertEquals("gemini-unversioned", profile.describe());
    }
}
