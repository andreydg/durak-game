package com.example.durakgame.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several tests block a write or a close on a latch; a regression must fail, not hang the build. */
@Timeout(10)
class GameWebSocketHandlerTest {
    private static final String GAME_PATH = "/ws/games/ABCD23";

    private final GameWebSocketHandler handler = new GameWebSocketHandler(new ObjectMapper());

    @Test
    void failedSessionCannotAbortABroadcastAndIsDropped() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession(GAME_PATH);
        handler.afterConnectionEstablished(session);
        session.sendFailure = new IllegalStateException("TEXT_FULL_WRITING");

        assertDoesNotThrow(() -> handler.broadcastGameUpdated("ABCD23", 4L));
        assertTrue(session.awaitClosed(Duration.ofSeconds(2)));
        assertDoesNotThrow(() -> handler.broadcastGameUpdated("ABCD23", 5L));

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
                handler.broadcastGameUpdated("ABCD23", 1L);
            });
            Future<?> second = pool.submit(() -> {
                await(start);
                handler.broadcastBotThinking("ABCD23", "bot", true, "planning attack...");
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
        handler.broadcastBotThinking("ABCD23", "bot", true, "planning defence...");
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
        handler.broadcastGameUpdated("ABCD23", 1L);
        handler.broadcastGameUpdated("ABCD23", 2L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMs < 500, "broadcasting waited " + elapsedMs + " ms for a slow client");
        assertTrue(fast.awaitMessageCount(2, Duration.ofMillis(500)), "the fast client waited for the slow one");
        assertTrue(slow.awaitMessageCount(2, Duration.ofSeconds(5)), "the slow client still gets every update");
    }

    @Test
    void aClientThatFallsTooFarBehindIsClosed() throws Exception {
        TestWebSocketSession stuck = new TestWebSocketSession(GAME_PATH);
        stuck.sendGate = new CountDownLatch(1);
        handler.afterConnectionEstablished(stuck);

        for (int i = 0; i <= SessionSender.QUEUE_CAPACITY + 1; i++) {
            handler.broadcastGameUpdated("ABCD23", (long) i);
        }
        stuck.sendGate.countDown();

        assertTrue(stuck.awaitClosed(Duration.ofSeconds(2)));
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), stuck.closeStatus.getCode());
        assertTrue(stuck.closedBy.startsWith("websocket-send-"), "closed by " + stuck.closedBy);
    }

    @Test
    void aStuckClientsCloseHoldsUpNeitherBroadcastsNorOtherClients() throws Exception {
        TestWebSocketSession stuck = new TestWebSocketSession(GAME_PATH);
        stuck.sendGate = new CountDownLatch(1);
        stuck.closeGate = new CountDownLatch(1);
        TestWebSocketSession healthy = new TestWebSocketSession(GAME_PATH);
        handler.afterConnectionEstablished(stuck);
        handler.afterConnectionEstablished(healthy);
        int updates = SessionSender.QUEUE_CAPACITY + 8;

        long slowestMs = 0;
        for (int i = 0; i < updates; i++) {
            long started = System.nanoTime();
            handler.broadcastGameUpdated("ABCD23", (long) i);
            slowestMs = Math.max(slowestMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (i == 0) {
                assertTrue(stuck.awaitSendAttempts(1, Duration.ofSeconds(2)));
            }
        }

        assertTrue(slowestMs < 200, "a broadcast waited " + slowestMs + " ms for the stuck client");
        assertTrue(healthy.awaitMessageCount(updates, Duration.ofSeconds(2)), "the healthy client missed updates");
        stuck.sendGate.countDown();
        assertTrue(stuck.awaitCloseCalls(1, Duration.ofSeconds(2)));
        assertTrue(stuck.closedBy.startsWith("websocket-send-"), "closed by " + stuck.closedBy);
        handler.broadcastGameUpdated("ABCD23", (long) updates);
        assertTrue(healthy.awaitMessageCount(updates + 1, Duration.ofSeconds(2)), "broadcasts go on while it closes");

        stuck.closeGate.countDown();
        assertTrue(stuck.awaitClosed(Duration.ofSeconds(2)));
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), stuck.closeStatus.getCode());
        assertEquals(1, stuck.closeCalls.get());
    }

    @Test
    void aSocketKeepsItsPlaceUntilItsConnectionCloses() throws Exception {
        WebSocketAdmission admission = new WebSocketAdmission(10, 1, 50, 1);
        GameWebSocketHandler capped = new GameWebSocketHandler(new ObjectMapper(), admission);
        TestWebSocketSession first = TestWebSocketSession.admitted(admission, GAME_PATH, "203.0.113.7");
        first.sendGate = new CountDownLatch(1);
        capped.afterConnectionEstablished(first);

        for (int i = 0; i <= SessionSender.QUEUE_CAPACITY + 1; i++) {
            capped.broadcastGameUpdated("ABCD23", (long) i);
        }

        // Too far behind and no longer broadcast to, but its connection is still open.
        assertEquals(1, admission.openSessions());
        assertThrows(IllegalStateException.class,
                () -> TestWebSocketSession.admitted(admission, GAME_PATH, "203.0.113.7"));

        first.sendGate.countDown();
        assertTrue(first.awaitClosed(Duration.ofSeconds(2)));
        capped.afterConnectionClosed(first, CloseStatus.SESSION_NOT_RELIABLE);
        capped.afterConnectionClosed(first, CloseStatus.SESSION_NOT_RELIABLE);

        assertEquals(0, admission.openSessions());
        TestWebSocketSession replacement = TestWebSocketSession.admitted(admission, GAME_PATH, "203.0.113.7");
        capped.afterConnectionEstablished(replacement);
        capped.broadcastGameUpdated("ABCD23", 99L);
        assertTrue(replacement.awaitMessageCount(1, Duration.ofSeconds(2)));
    }

    @Test
    void aSocketWhoseHandshakeReservationExpiredIsClosed() throws Exception {
        AtomicLong nanoTime = new AtomicLong();
        WebSocketAdmission admission = new WebSocketAdmission(10, 10, 50, 1, nanoTime::get);
        GameWebSocketHandler capped = new GameWebSocketHandler(new ObjectMapper(), admission);
        TestWebSocketSession late = TestWebSocketSession.admitted(admission, GAME_PATH, "203.0.113.8");
        nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(WebSocketAdmission.OPEN_GRACE_SECONDS + 1));
        assertEquals(0, admission.openSessions());

        capped.afterConnectionEstablished(late);
        capped.broadcastGameUpdated("ABCD23", 1L);

        assertTrue(late.awaitClosed(Duration.ofSeconds(1)));
        assertEquals(CloseStatus.SERVICE_OVERLOAD.getCode(), late.closeStatus.getCode());
        assertEquals(0, late.sendAttempts.get());
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
