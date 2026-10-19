package com.example.durakgame.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
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
        // protocol guarantees; the READY snapshot taken at connection time is.
        assertTrue(session.awaitMessageCount(1, Duration.ofSeconds(1)));
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

            assertTrue(first.awaitMessageCount(1, Duration.ofSeconds(1)));
            assertTrue(second.awaitMessageCount(1, Duration.ofSeconds(1)));
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
        assertTrue(session.awaitMessageCount(1, Duration.ofSeconds(1)));
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
        assertTrue(session.awaitMessageCount(1, Duration.ofSeconds(1)));
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
    void aSocketKeepsItsPlaceUntilItsConnectionCloses() throws Exception {
        WebSocketAdmission admission = new WebSocketAdmission(1, 1, 50, 1);
        LobbyWebSocketHandler capped = new LobbyWebSocketHandler(objectMapper, admission);
        try {
            TestWebSocketSession first = TestWebSocketSession.admitted(admission, "/ws/lobbies", "198.51.100.1");
            capped.afterConnectionEstablished(first);
            assertTrue(first.awaitMessageCount(1, Duration.ofSeconds(1)));
            assertThrows(IllegalStateException.class,
                    () -> TestWebSocketSession.admitted(admission, "/ws/lobbies", "198.51.100.2"), "total cap");

            capped.afterConnectionClosed(first, CloseStatus.NORMAL);
            capped.afterConnectionClosed(first, CloseStatus.NORMAL);

            TestWebSocketSession next = TestWebSocketSession.admitted(admission, "/ws/lobbies", "198.51.100.2");
            capped.afterConnectionEstablished(next);
            assertTrue(next.awaitMessageCount(1, Duration.ofSeconds(1)));
            assertEquals(1, admission.openSessions());
        } finally {
            capped.close();
        }
    }

    @Test
    void aSlowClientDoesNotDelayOtherClients() throws Exception {
        TestWebSocketSession slow = new TestWebSocketSession("/ws/lobbies");
        TestWebSocketSession fast = new TestWebSocketSession("/ws/lobbies");
        handler.afterConnectionEstablished(slow);
        handler.afterConnectionEstablished(fast);
        assertTrue(slow.awaitMessageCount(1, Duration.ofSeconds(1)));
        assertTrue(fast.awaitMessageCount(1, Duration.ofSeconds(1)));
        slow.sendDelayMs = 2_000;

        handler.lobbiesChanged();

        assertTrue(fast.awaitMessageCount(2, Duration.ofMillis(500)), "the fast client waited for the slow one");
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
