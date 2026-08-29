package com.example.durakgame.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameWebSocketHandlerTest {

    private final GameWebSocketHandler handler = new GameWebSocketHandler(new ObjectMapper());

    @Test
    void failedSessionCannotAbortABroadcastAndIsQuarantined() throws Exception {
        StubSession session = new StubSession();
        handler.afterConnectionEstablished(session);
        session.sendFailure = new IllegalStateException("TEXT_FULL_WRITING");

        assertDoesNotThrow(() -> handler.broadcastGameUpdated("ABC123", 4L));
        assertDoesNotThrow(() -> handler.broadcastGameUpdated("ABC123", 5L));

        assertEquals(1, session.sendAttempts.get());
    }

    @Test
    void concurrentBroadcastsSerializeWritesPerSession() throws Exception {
        StubSession session = new StubSession();
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

        assertEquals(1, session.maxInFlight.get());
        assertEquals(2, session.messages.size());
    }

    @Test
    void reconnectingSessionReceivesCurrentBotThinkingState() throws Exception {
        handler.broadcastBotThinking("ABC123", "bot", true, "planning defence...");
        StubSession session = new StubSession();

        handler.afterConnectionEstablished(session);

        assertEquals(1, session.messages.size());
        String payload = session.messages.getFirst().getPayload();
        assertTrue(payload.contains("\"thinking\":true"));
        assertTrue(payload.contains("planning defence..."));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static final class StubSession implements WebSocketSession {
        private final AtomicInteger sendAttempts = new AtomicInteger();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final List<TextMessage> messages = new ArrayList<>();
        private volatile boolean open = true;
        private volatile long sendDelayMs;
        private volatile RuntimeException sendFailure;
        private int textLimit = 64 * 1024;
        private int binaryLimit = 64 * 1024;

        @Override public String getId() { return "session-1"; }
        @Override public URI getUri() { return URI.create("ws://localhost/ws/games/ABC123"); }
        @Override public HttpHeaders getHandshakeHeaders() { return HttpHeaders.EMPTY; }
        @Override public Map<String, Object> getAttributes() { return Map.of(); }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) { textLimit = messageSizeLimit; }
        @Override public int getTextMessageSizeLimit() { return textLimit; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) { binaryLimit = messageSizeLimit; }
        @Override public int getBinaryMessageSizeLimit() { return binaryLimit; }
        @Override public List<WebSocketExtension> getExtensions() { return List.of(); }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            sendAttempts.incrementAndGet();
            if (sendFailure != null) {
                throw sendFailure;
            }
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                if (sendDelayMs > 0) {
                    Thread.sleep(sendDelayMs);
                }
                messages.add((TextMessage) message);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        @Override public void close(CloseStatus status) { open = false; }
    }
}
