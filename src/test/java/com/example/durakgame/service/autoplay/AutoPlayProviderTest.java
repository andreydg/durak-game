package com.example.durakgame.service.autoplay;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which engine plays bots, and the Claude defaults production starts from. */
class AutoPlayProviderTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(HeuristicAutoPlayDecisionEngine.class, AnthropicFederation.class,
                    GeminiAutoPlayDecisionEngine.class, ClaudeAutoPlayDecisionEngine.class,
                    AutoPlayProviderCheck.class);

    @Test
    void geminiPlaysUntilTheProviderIsSwitched() {
        runner.run(context -> {
            assertInstanceOf(GeminiAutoPlayDecisionEngine.class, context.getBean(AutoPlayDecisionEngine.class));
            assertTrue(context.getBeansOfType(ClaudeAutoPlayDecisionEngine.class).isEmpty());
        });
    }

    @Test
    void theAnthropicProviderPlaysClaude() {
        for (String provider : new String[] {"anthropic", "Anthropic"}) {
            runner.withPropertyValues("autoplay.provider=" + provider).run(context -> {
                assertInstanceOf(ClaudeAutoPlayDecisionEngine.class, context.getBean(AutoPlayDecisionEngine.class));
                assertTrue(context.getBeansOfType(GeminiAutoPlayDecisionEngine.class).isEmpty());
            });
        }
    }

    @Test
    void anUnknownProviderStopsStartup() {
        for (String provider : new String[] {"claude", ""}) {
            runner.withPropertyValues("autoplay.provider=" + provider).run(context -> {
                Throwable failure = context.getStartupFailure();
                assertNotNull(failure, "started with provider \"" + provider + "\"");
                while (failure.getCause() != null) {
                    failure = failure.getCause();
                }
                assertEquals("autoplay.provider must be one of [anthropic, gemini], not \"" + provider + "\"",
                        failure.getMessage());
            });
        }
        // Spring's condition would not match a padded value either, so it must not pass the check.
        assertThrows(IllegalStateException.class, () -> new AutoPlayProviderCheck(" anthropic"));
        assertThrows(IllegalStateException.class, () -> new AutoPlayProviderCheck(null));
    }

    @Test
    void productionStartsOnGeminiWithClaudeReadyToSwitchTo() throws IOException {
        Properties properties = applicationProperties();

        assertEquals("${AUTOPLAY_PROVIDER:gemini}", properties.getProperty("autoplay.provider"));
        assertEquals("${AUTOPLAY_ANTHROPIC_MODEL:claude-opus-5-5}", properties.getProperty("autoplay.anthropic.model"));
        assertEquals("${AUTOPLAY_ANTHROPIC_EFFORT:medium}", properties.getProperty("autoplay.anthropic.effort"));
        assertEquals("${AUTOPLAY_ANTHROPIC_SIMPLE_EFFORT:low}",
                properties.getProperty("autoplay.anthropic.simple-effort"));
        assertEquals("${AUTOPLAY_ANTHROPIC_MAX_TOKENS:8192}", properties.getProperty("autoplay.anthropic.max-tokens"));
        assertEquals("${AUTOPLAY_ANTHROPIC_INFERENCE_GEO:global}",
                properties.getProperty("autoplay.anthropic.inference-geo"));
        for (String id : new String[] {"organization-id", "workspace-id", "service-account-id", "federation-rule-id"}) {
            String env = "AUTOPLAY_ANTHROPIC_" + id.toUpperCase().replace('-', '_');
            assertEquals("${" + env + ":}", properties.getProperty("autoplay.anthropic." + id),
                    "federation ids come only from the environment");
        }
        assertEquals("claude-opus-5-5", ClaudeSettings.DEFAULT_MODEL);
        assertEquals("medium", ClaudeSettings.DEFAULT_EFFORT);
        assertEquals("low", ClaudeSettings.DEFAULT_SIMPLE_EFFORT);
        assertEquals(8192, ClaudeSettings.DEFAULT_MAX_TOKENS);
        assertEquals("global", ClaudeSettings.DEFAULT_INFERENCE_GEO);
    }

    /**
     * What the deploy script relies on when it sets a variable to the empty string: the service then
     * reads it as empty (no inference_geo field, no workspace_id), and only a variable it does not
     * have falls back to the default.
     */
    @Test
    void anEmptyVariableIsNotAMissingOne() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addLast(new PropertiesPropertySource("application", applicationProperties()));
        sources.addFirst(new SystemEnvironmentPropertySource("service", Map.of(
                "AUTOPLAY_ANTHROPIC_INFERENCE_GEO", "", "AUTOPLAY_ANTHROPIC_WORKSPACE_ID", "")));

        assertEquals("", environment.getProperty("autoplay.anthropic.inference-geo"));
        assertEquals("", environment.getProperty("autoplay.anthropic.workspace-id"));

        sources.remove("service");
        assertEquals("global", environment.getProperty("autoplay.anthropic.inference-geo"));
        assertEquals("", environment.getProperty("autoplay.anthropic.workspace-id"));
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
