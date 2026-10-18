package com.example.durakgame.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameWebSocketHandlerTest {
    private static final String GAME_PATH = "/ws/games/ABC123";

    private final GameWebSocketHandler handler = new GameWebSocketHandler(new ObjectMapper());

    @Test
    void failedSessionCannotAbortABroadcastAndIsDropped() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession(GAME_PATH);
        handler.afterConnectionEstablished(session);
        session.sendFailure = new IllegalStateException("TEXT_FULL_WRITING");

        assertDoesNotThrow(() -> handler.broadcastGameUpdated("ABC123", 4L));
        assertTrue(session.awaitClosed(Duration.ofSeconds(2)));
        assertDoesNotThrow(() -> handler.broadcastGameUpdated("ABC123", 5L));

        assertEquals(1, session.sendAttempts.get());
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), session.closeStatus.getCode());
    }

    @Test
    void concurrentBroadcastsAreWrittenOneAtATime() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession(GAME_PATH);
        session.sendDelayMs = 100;
        handler.afterConnectionEstablished(session);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                await(start);
                handler.broadcastGameUpdated("ABC123", 1L);
            });
            Future<?> second = pool.submit(() -> {
                await(start);
                handler.broadcastBotThinking("ABC123", "bot", true, "planning attack...");
            });
            start.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(session.awaitMessageCount(2, Duration.ofSeconds(2)));
        assertEquals(1, session.maxInFlight.get());
    }

    @Test
    void reconnectingSessionReceivesCurrentBotThinkingState() throws Exception {
        handler.broadcastBotThinking("ABC123", "bot", true, "planning defence...");
        TestWebSocketSession session = new TestWebSocketSession(GAME_PATH);

        handler.afterConnectionEstablished(session);

        assertTrue(session.awaitMessageCount(1, Duration.ofSeconds(2)));
        String payload = session.messages.getFirst().getPayload();
        assertTrue(payload.contains("\"thinking\":true"));
        assertTrue(payload.contains("planning defence..."));
    }

    @Test
    void aSlowClientHoldsUpNeitherTheBroadcasterNorOtherClients() throws Exception {
        TestWebSocketSession slow = new TestWebSocketSession(GAME_PATH);
        slow.sendDelayMs = 1_000;
        TestWebSocketSession fast = new TestWebSocketSession(GAME_PATH);
        handler.afterConnectionEstablished(slow);
        handler.afterConnectionEstablished(fast);

        long started = System.nanoTime();
        handler.broadcastGameUpdated("ABC123", 1L);
        handler.broadcastGameUpdated("ABC123", 2L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMs < 500, "broadcasting waited " + elapsedMs + " ms for a slow client");
        assertTrue(fast.awaitMessageCount(2, Duration.ofMillis(500)), "the fast client waited for the slow one");
        assertTrue(slow.awaitMessageCount(2, Duration.ofSeconds(5)), "the slow client still gets every update");
    }

    @Test
    void aClientThatFallsTooFarBehindIsClosed() throws Exception {
        TestWebSocketSession stuck = new TestWebSocketSession(GAME_PATH);
        stuck.sendDelayMs = 10_000;
        handler.afterConnectionEstablished(stuck);

        for (int i = 0; i <= SessionSender.QUEUE_CAPACITY + 1; i++) {
            handler.broadcastGameUpdated("ABC123", (long) i);
        }

        assertTrue(stuck.awaitClosed(Duration.ofSeconds(2)));
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), stuck.closeStatus.getCode());
    }

    @Test
    void sessionsBeyondTheClientCapAreClosedUntilOneLeaves() throws Exception {
        GameWebSocketHandler capped = new GameWebSocketHandler(new ObjectMapper(), new WebSocketAdmission(10, 2, 1));
        TestWebSocketSession first = new TestWebSocketSession(GAME_PATH, "203.0.113.7");
        TestWebSocketSession second = new TestWebSocketSession(GAME_PATH, "203.0.113.7");
        TestWebSocketSession third = new TestWebSocketSession(GAME_PATH, "203.0.113.7");
        TestWebSocketSession otherClient = new TestWebSocketSession(GAME_PATH, "198.51.100.9");

        capped.afterConnectionEstablished(first);
        capped.afterConnectionEstablished(second);
        capped.afterConnectionEstablished(third);
        capped.afterConnectionEstablished(otherClient);

        assertFalse(third.isOpen());
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), third.closeStatus.getCode());
        assertTrue(otherClient.isOpen());

        capped.afterConnectionClosed(first, CloseStatus.NORMAL);
        TestWebSocketSession replacement = new TestWebSocketSession(GAME_PATH, "203.0.113.7");
        capped.afterConnectionEstablished(replacement);

        assertTrue(replacement.isOpen());
        capped.broadcastGameUpdated("ABC123", 1L);
        assertTrue(replacement.awaitMessageCount(1, Duration.ofSeconds(2)));
        assertEquals(0, third.messages.size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
