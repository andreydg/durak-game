package com.example.durakgame.service.autoplay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GeminiConfigurationTest {

    @Test
    void productionDefaultsToStableGemini37Flash() throws IOException {
        Properties properties = applicationProperties();

        assertEquals("gemini-3.7-flash", GeminiAutoPlayDecisionEngine.DEFAULT_MODEL);
        assertEquals(
                "${AUTOPLAY_GEMINI_MODEL:gemini-3.7-flash}",
                properties.getProperty("autoplay.gemini.model")
        );
    }

    @Test
    void apiKeyComesOnlyFromTheEnvironment() throws IOException {
        Properties properties = applicationProperties();

        assertEquals("${GEMINI_API_KEY:}", properties.getProperty("autoplay.gemini.api-key"));
    }

    private Properties applicationProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(input, "application.properties must be available on the test classpath");
            properties.load(input);
        }
        return properties;
    }
}
