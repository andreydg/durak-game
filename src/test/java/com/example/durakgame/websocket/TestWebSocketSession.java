package com.example.durakgame.websocket;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Reusable websocket test double with write-overlap, blocking, and failure instrumentation. */
final class TestWebSocketSession implements WebSocketSession {
    private static final AtomicInteger IDS = new AtomicInteger();
    private final String id = "session-" + IDS.incrementAndGet();
    final Map<String, Object> attributes = new ConcurrentHashMap<>();
    final AtomicInteger sendAttempts = new AtomicInteger();
    final AtomicInteger inFlight = new AtomicInteger();
    final AtomicInteger maxInFlight = new AtomicInteger();
    final AtomicInteger closeCalls = new AtomicInteger();
    final List<TextMessage> messages = new CopyOnWriteArrayList<>();
    volatile boolean open = true;
    volatile long sendDelayMs;
    volatile RuntimeException sendFailure;
    volatile CloseStatus closeStatus;
    /** Name of the thread that called close. */
    volatile String closedBy;
    /** When set, every write waits for it, like a write to a client that has stopped reading. */
    volatile CountDownLatch sendGate;
    /** When set, close waits for it, like Tomcat's close frame queued behind a stuck write. */
    volatile CountDownLatch closeGate;
    private final URI uri;
    private int textLimit = 64 * 1024;
    private int binaryLimit = 64 * 1024;

    TestWebSocketSession(String path) {
        this.uri = URI.create("ws://localhost" + path);
    }

    /**
     * A session that came through {@code admission}'s handshake from {@code clientAddress} (as
     * Cloud Run forwards it) and was upgraded, so it holds a reservation.
     */
    static TestWebSocketSession admitted(WebSocketAdmission admission, String path, String clientAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-Forwarded-For", clientAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletServerHttpRequest serverRequest = new ServletServerHttpRequest(request);
        ServletServerHttpResponse serverResponse = new ServletServerHttpResponse(response);
        TestWebSocketSession session = new TestWebSocketSession(path);
        if (!admission.beforeHandshake(serverRequest, serverResponse, null, session.attributes)) {
            throw new IllegalStateException("handshake refused with HTTP " + response.getStatus());
        }
        response.setStatus(101);
        admission.afterHandshake(serverRequest, serverResponse, null, null);
        return session;
    }

    boolean awaitClosed(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (open && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return !open;
    }

    boolean awaitCloseCalls(int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (closeCalls.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return closeCalls.get() >= expected;
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

    @Override public String getId() { return id; }
    @Override public URI getUri() { return uri; }
    @Override public HttpHeaders getHandshakeHeaders() { return HttpHeaders.EMPTY; }
    @Override public Map<String, Object> getAttributes() { return attributes; }
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
            await(sendGate);
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
    @Override public void close() { close(CloseStatus.NORMAL); }

    @Override
    public void close(CloseStatus status) {
        closeCalls.incrementAndGet();
        closedBy = Thread.currentThread().getName();
        try {
            await(closeGate);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        closeStatus = status;
        open = false;
    }

    private static void await(CountDownLatch gate) throws InterruptedException {
        if (gate != null) {
            gate.await();
        }
    }
}
