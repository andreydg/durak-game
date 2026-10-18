package com.example.durakgame.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        stuck.sendDelayMs = 10_000;
        SessionSender sender = new SessionSender(stuck);

        boolean refused = false;
        for (int i = 0; i <= SessionSender.QUEUE_CAPACITY + 1 && !refused; i++) {
            refused = !sender.send(new TextMessage("m" + i));
        }

        assertTrue(refused, "a full queue must refuse further messages");
        assertTrue(stuck.awaitClosed(Duration.ofSeconds(2)));
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), stuck.closeStatus.getCode());
        assertFalse(sender.send(new TextMessage("after close")));
    }

    @Test
    void aFailedWriteClosesTheSession() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession("/ws/lobbies");
        session.sendFailure = new IllegalStateException("TEXT_FULL_WRITING");
        SessionSender sender = new SessionSender(session);

        sender.send(new TextMessage("m"));

        assertTrue(session.awaitClosed(Duration.ofSeconds(2)));
        assertEquals(CloseStatus.SESSION_NOT_RELIABLE.getCode(), session.closeStatus.getCode());
        assertFalse(sender.send(new TextMessage("after failure")));
    }

    @Test
    void aStoppedSenderAcceptsNothing() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession("/ws/lobbies");
        SessionSender sender = new SessionSender(session);

        sender.stop();

        assertFalse(sender.send(new TextMessage("m")));
        Thread.sleep(50);
        assertEquals(List.of(), session.messages);
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
}
