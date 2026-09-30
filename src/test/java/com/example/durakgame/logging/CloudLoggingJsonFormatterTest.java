package com.example.durakgame.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudLoggingJsonFormatterTest {

    private final CloudLoggingJsonFormatter formatter = new CloudLoggingJsonFormatter();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void writesOneCloudLoggingEntryPerEvent() throws Exception {
        String line = formatter.format(event(Level.WARN, "auth_rejected code={} reason={}", null, "ABC123", "token_mismatch"));

        assertTrue(line.endsWith("\n"));
        assertEquals(1, line.strip().lines().count(), "exactly one line per event");
        JsonNode entry = json.readTree(line);
        assertEquals("WARNING", entry.get("severity").asText());
        assertEquals("auth_rejected code=ABC123 reason=token_mismatch", entry.get("message").asText());
        assertEquals("com.example.Test", entry.get("logger").asText());
        assertTrue(entry.get("time").asText().endsWith("Z"));
    }

    @Test
    void keepsTheStackTraceInsideTheSameEntry() throws Exception {
        String line = formatter.format(event(Level.ERROR, "autoplay_background_failed", new IllegalStateException("boom")));

        assertEquals(1, line.strip().lines().count());
        JsonNode entry = json.readTree(line);
        assertEquals("ERROR", entry.get("severity").asText());
        String message = entry.get("message").asText();
        assertTrue(message.startsWith("autoplay_background_failed\njava.lang.IllegalStateException: boom"), message);
        assertTrue(message.contains("\tat "), message);
    }

    @Test
    void mapsLogbackLevelsToCloudLoggingSeverities() {
        assertEquals("ERROR", CloudLoggingJsonFormatter.severity(Level.ERROR));
        assertEquals("WARNING", CloudLoggingJsonFormatter.severity(Level.WARN));
        assertEquals("INFO", CloudLoggingJsonFormatter.severity(Level.INFO));
        assertEquals("DEBUG", CloudLoggingJsonFormatter.severity(Level.DEBUG));
        assertEquals("DEBUG", CloudLoggingJsonFormatter.severity(Level.TRACE));
    }

    private static LoggingEvent event(Level level, String message, Throwable throwable, Object... args) {
        LoggerContext context = new LoggerContext();
        return new LoggingEvent("fqcn", context.getLogger("com.example.Test"), level, message, throwable, args);
    }
}
