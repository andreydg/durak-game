package com.example.durakgame.websocket;

import jakarta.websocket.CloseReason;
import org.apache.tomcat.websocket.WsSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Several tests block a write or a close on a latch; a regression must fail, not hang the build. */
@Timeout(10)
class SessionSenderTest {

    @Test
    void deliversMessagesInOrder() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession("/ws/lobbies");
        SessionSender sender = new SessionSender(session);

        IntStream.range(0, 10).forEach(i -> assertTrue(sender.send(new TextMessage("m" + i))));

        assertTrue(session.awaitMessageCount(10, Duration.ofSeconds(2)));
        assertEquals(IntStream.range(0, 10).mapToObj(i -> "m" + i).toList(),
                session.messages.stream().map(TextMessage::getPayload).toList());
        sender.stop();
    }

    @Test
    void sendingNeverWaitsForTheClient() {
        TestWebSocketSession slow = new TestWebSocketSession("/ws/lobbies");
        slow.sendDelayMs = 1_000;
        SessionSender sender = new SessionSender(slow);

        long started = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            assertTrue(sender.send(new TextMessage("m" + i)));
        }

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 200);
        sender.stop();
    }

    @Test
    void aClientThatFallsTooFarBehindIsClosed() throws Exception {
        TestWebSocketSession stuck = new TestWebSocketSession("/ws/lobbies");
        stuck.sendDelayMs = 300;
        SessionSender sender = new SessionSender(stuck);

        boolean refused = false;
        for (int i = 0; i <= SessionSender.QUEUE_CAPACITY + 1 && !refused; i++) {
            refused = !sender.send(new TextMessage("m" + i));
        }

        assertTrue(refused, "a full queue must refuse further messages");
        assertFalse(sender.send(new TextMessage("after overflow")));
        // The write in flight finishes (or times out, on Tomcat) before the sender closes.
        assertTrue(stuck.awaitClosed(Duration.ofSeconds(2)));
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), stuck.closeStatus.getCode());
    }

    /**
     * The reviewer's probe: hold the write in flight, fill the queue, and make close itself block.
     * The broadcaster that overflows the queue must return at once all the same.
     */
    @Test
    void aFullQueueNeverMakesTheCallerWaitForTheClose() throws Exception {
        TestWebSocketSession stuck = new TestWebSocketSession("/ws/lobbies");
        stuck.sendGate = new CountDownLatch(1);
        stuck.closeGate = new CountDownLatch(1);
        SessionSender sender = new SessionSender(stuck);
        assertTrue(sender.send(new TextMessage("in flight")));
        assertTrue(stuck.awaitSendAttempts(1, Duration.ofSeconds(2)));
        for (int i = 0; i < SessionSender.QUEUE_CAPACITY; i++) {
            assertTrue(sender.send(new TextMessage("queued " + i)), "queue slot " + i);
        }

        long started = System.nanoTime();
        boolean accepted = sender.send(new TextMessage("one too many"));
        long overflowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertFalse(accepted);
        assertTrue(overflowMs < 200, "the overflowing send waited " + overflowMs + " ms");
        assertFalse(sender.send(new TextMessage("after overflow")), "a closing session takes nothing more");
        assertEquals(0, stuck.closeCalls.get(), "the close waits for the write in flight, off the caller's thread");

        stuck.sendGate.countDown();
        assertTrue(stuck.awaitCloseCalls(1, Duration.ofSeconds(2)), "the sender closes once its write returns");
        assertTrue(stuck.closedBy.startsWith("websocket-send-"), "closed by " + stuck.closedBy);
        assertFalse(sender.send(new TextMessage("while closing")));
        assertFalse(sender.isDone(), "close is still blocked");

        stuck.closeGate.countDown();
        assertTrue(stuck.awaitClosed(Duration.ofSeconds(2)));
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), stuck.closeStatus.getCode());
        assertTrue(awaitDone(sender), "the sender's thread ends after closing");
        assertEquals(1, stuck.closeCalls.get());
        // The backlog was dropped, not written after the client had already fallen too far behind.
        assertEquals(List.of("in flight"), stuck.messages.stream().map(TextMessage::getPayload).toList());
    }

    @Test
    void aFailedWriteClosesTheSessionFromTheSendersThread() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession("/ws/lobbies");
        session.sendFailure = new IllegalStateException("TEXT_FULL_WRITING");
        SessionSender sender = new SessionSender(session);

        sender.send(new TextMessage("m"));

        assertTrue(session.awaitClosed(Duration.ofSeconds(2)));
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), session.closeStatus.getCode());
        assertTrue(session.closedBy.startsWith("websocket-send-"), "closed by " + session.closedBy);
        assertFalse(sender.send(new TextMessage("after failure")));
        assertTrue(awaitDone(sender));
        assertEquals(1, session.closeCalls.get());
    }

    @Test
    void aStoppedSenderAcceptsNothingAndClosesNothing() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession("/ws/lobbies");
        SessionSender sender = new SessionSender(session);

        sender.stop();
        sender.stop();

        assertFalse(sender.send(new TextMessage("m")));
        assertTrue(awaitDone(sender));
        assertEquals(List.of(), session.messages);
        assertEquals(0, session.closeCalls.get(), "the connection is already gone");
    }

    @Test
    void stoppingWhileClosingIsHarmless() throws Exception {
        TestWebSocketSession stuck = new TestWebSocketSession("/ws/lobbies");
        stuck.closeGate = new CountDownLatch(1);
        stuck.sendFailure = new IllegalStateException("broken pipe");
        SessionSender sender = new SessionSender(stuck);
        sender.send(new TextMessage("m"));
        assertTrue(stuck.awaitCloseCalls(1, Duration.ofSeconds(2)));

        // afterConnectionClosed can run while the close is still writing its frame.
        sender.stop();
        stuck.closeGate.countDown();

        assertTrue(awaitDone(sender));
        assertEquals(1, stuck.closeCalls.get());
    }

    @Test
    void limitsHowLongTomcatMayBlockOnOneWrite() {
        NativeWebSocketSession session = mock(NativeWebSocketSession.class);
        jakarta.websocket.Session tomcatSession = mock(jakarta.websocket.Session.class);
        Map<String, Object> userProperties = new HashMap<>();
        when(session.getId()).thenReturn("native-1");
        when(session.isOpen()).thenReturn(true);
        when(session.getNativeSession(jakarta.websocket.Session.class)).thenReturn(tomcatSession);
        when(tomcatSession.getUserProperties()).thenReturn(userProperties);

        new SessionSender(session).stop();

        assertEquals(SessionSender.SEND_TIMEOUT_MS,
                userProperties.get("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT"));
    }

    /** A plain close would hold the connection open for Tomcat's 30 s close handshake. */
    @Test
    void onTomcatTheConnectionIsDroppedRightAfterTheCloseFrame() throws Exception {
        NativeWebSocketSession session = mock(NativeWebSocketSession.class);
        WsSession tomcatSession = mock(WsSession.class);
        CountDownLatch write = new CountDownLatch(1);
        when(session.getId()).thenReturn("native-2");
        when(session.isOpen()).thenReturn(true);
        when(session.getNativeSession(jakarta.websocket.Session.class)).thenReturn(tomcatSession);
        when(tomcatSession.getUserProperties()).thenReturn(new HashMap<>());
        doAnswer(invocation -> {
            write.await();
            return null;
        }).when(session).sendMessage(any());
        SessionSender sender = new SessionSender(session);

        for (int i = 0; i <= SessionSender.QUEUE_CAPACITY + 1; i++) {
            sender.send(new TextMessage("m" + i));
        }
        write.countDown();

        verify(tomcatSession, timeout(2_000)).doClose(
                argThat((CloseReason reason) -> reason.getCloseCode().getCode() == CloseStatus.SESSION_NOT_RELIABLE.getCode()),
                any(CloseReason.class), eq(true));
        verify(session, never()).close(any(CloseStatus.class));
    }

    private static boolean awaitDone(SessionSender sender) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!sender.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return sender.isDone();
    }
}
