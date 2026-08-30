package com.example.durakgame.websocket;

import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Reusable websocket test double with write-overlap and failure instrumentation. */
final class TestWebSocketSession implements WebSocketSession {
    final AtomicInteger sendAttempts = new AtomicInteger();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    final List<TextMessage> messages = new CopyOnWriteArrayList<>();
    volatile boolean open = true;
    volatile long sendDelayMs;
    volatile RuntimeException sendFailure;
    volatile CloseStatus closeStatus;
    private final URI uri;
    private int textLimit = 64 * 1024;
    private int binaryLimit = 64 * 1024;

    TestWebSocketSession(String path) {
        this.uri = URI.create("ws://localhost" + path);
    }

    boolean awaitMessageCount(int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (messages.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return messages.size() >= expected;
    }

    boolean awaitSendAttempts(int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (sendAttempts.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return sendAttempts.get() >= expected;
    }

    @Override public String getId() { return "session-1"; }
    @Override public URI getUri() { return uri; }
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
    @Override public void close(CloseStatus status) { open = false; closeStatus = status; }
}
