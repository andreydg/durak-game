package com.example.durakgame.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyWebSocketHandlerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LobbyWebSocketHandler handler = new LobbyWebSocketHandler(objectMapper);

    @AfterEach
    void stopExecutor() {
        handler.close();
    }

    @Test
    void connectionReceivesReadyMessageWithCurrentRevision() throws Exception {
        handler.lobbiesChanged();
        TestWebSocketSession session = new TestWebSocketSession("/ws/lobbies");

        handler.afterConnectionEstablished(session);

        // The already-scheduled revision broadcast may race with this connection and deliver an
        // equivalent LOBBIES_CHANGED before or after READY. Ordering and message count are not
        // protocol guarantees; the synchronous READY snapshot is.
        Map<String, Object> ready = payloadOfType(session, "LOBBIES_READY");
        assertFalse(String.valueOf(ready.get("streamId")).isBlank());
        assertEquals(1, ((Number) ready.get("revision")).intValue());
    }

    @Test
    void restartedHandlerUsesANewRevisionStream() throws Exception {
        TestWebSocketSession first = new TestWebSocketSession("/ws/lobbies");
        handler.afterConnectionEstablished(first);
        LobbyWebSocketHandler restarted = new LobbyWebSocketHandler(objectMapper);
        TestWebSocketSession second = new TestWebSocketSession("/ws/lobbies");
        try {
            restarted.afterConnectionEstablished(second);

            assertNotEquals(
                    payload(first.messages.getFirst().getPayload()).get("streamId"),
                    payload(second.messages.getFirst().getPayload()).get("streamId")
            );
        } finally {
            restarted.close();
        }
    }

    @Test
    void invalidationsAdvanceMonotonicRevision() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession("/ws/lobbies");
        handler.afterConnectionEstablished(session);

        handler.lobbiesChanged();
        assertTrue(session.awaitMessageCount(2, Duration.ofSeconds(1)));
        handler.lobbiesChanged();
        assertTrue(session.awaitMessageCount(3, Duration.ofSeconds(1)));

        assertEquals(1, revision(session.messages.get(1)));
        assertEquals(2, revision(session.messages.get(2)));
    }

    @Test
    void failedSessionCannotAbortPublishingAndIsQuarantined() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession("/ws/lobbies");
        handler.afterConnectionEstablished(session);
        session.sendFailure = new IllegalStateException("TEXT_FULL_WRITING");

        assertDoesNotThrow(handler::lobbiesChanged);
        assertTrue(session.awaitSendAttempts(2, Duration.ofSeconds(1)));
        assertDoesNotThrow(handler::lobbiesChanged);
        Thread.sleep(50);

        assertEquals(2, session.sendAttempts.get(), "failed session should be removed after one failed write");
    }

    @Test
    void burstInvalidationsAreCoalescedWithoutLosingLatestRevision() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession("/ws/lobbies");
        handler.afterConnectionEstablished(session);
        session.sendDelayMs = 75;

        for (int i = 0; i < 20; i++) {
            handler.lobbiesChanged();
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (revision(session.messages.getLast()) < 20 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(20, revision(session.messages.getLast()));
        assertTrue(session.messages.size() <= 3,
                "ready plus at most two coalesced writes expected, got " + session.messages.size());
        assertEquals(1, session.maxInFlight.get());
    }

    @Test
    void globalConnectionSetIsBounded() throws Exception {
        for (int i = 0; i < 500; i++) {
            handler.afterConnectionEstablished(new TestWebSocketSession("/ws/lobbies"));
        }
        TestWebSocketSession overflow = new TestWebSocketSession("/ws/lobbies");

        handler.afterConnectionEstablished(overflow);

        assertFalse(overflow.isOpen());
        assertEquals(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION.getCode(),
                overflow.closeStatus.getCode());
    }

    private Map<String, Object> payload(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() { });
    }

    private Map<String, Object> payloadOfType(TestWebSocketSession session, String type) throws Exception {
        for (org.springframework.web.socket.TextMessage message : session.messages) {
            Map<String, Object> candidate = payload(message.getPayload());
            if (type.equals(candidate.get("type"))) {
                return candidate;
            }
        }
        throw new AssertionError("Expected websocket message type " + type + " but got " + session.messages);
    }

    private long revision(org.springframework.web.socket.TextMessage message) throws Exception {
        return ((Number) payload(message.getPayload()).get("revision")).longValue();
    }
}
