package com.example.durakgame.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Writes one session's outgoing messages, in order, from the session's own virtual thread.
 * Broadcasters (HTTP requests, bot turns, the lobby publisher) only enqueue, so a slow or dead
 * client can never hold them up. The queue is bounded and every write has a deadline: a client
 * that falls that far behind, or stops reading, is closed and reconnects like any dropped client.
 */
final class SessionSender {
    static final int QUEUE_CAPACITY = 32;
    static final long SEND_TIMEOUT_MS = 5_000;
    /** Tomcat's per-session limit for a blocking write (20 seconds by default), read on every write. */
    private static final String TOMCAT_BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";
    private static final Logger log = LoggerFactory.getLogger(SessionSender.class);

    private final WebSocketSession session;
    private final BlockingQueue<TextMessage> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread thread;
    private volatile boolean stopped;

    SessionSender(WebSocketSession session) {
        this.session = session;
        limitWriteTime(session);
        this.thread = Thread.ofVirtual().name("websocket-send-" + session.getId()).unstarted(this::drain);
        thread.start();
    }

    /** Queues a message without blocking; false once the session is closed or too far behind. */
    boolean send(TextMessage message) {
        if (stopped || !session.isOpen()) {
            return false;
        }
        if (queue.offer(message)) {
            return true;
        }
        log.info("websocket_session_dropped reason=send_queue_full sessionId={}", session.getId());
        close();
        return false;
    }

    void stop() {
        stopped = true;
        if (Thread.currentThread() != thread) {
            thread.interrupt();
        }
    }

    private void drain() {
        try {
            while (!stopped) {
                TextMessage message = queue.take();
                if (!session.isOpen()) {
                    return;
                }
                session.sendMessage(message);
            }
        } catch (InterruptedException stop) {
            // stop() was called: the session is gone.
        } catch (IOException | RuntimeException ex) {
            log.info("websocket_session_dropped reason=send_failed sessionId={} error={}",
                    session.getId(), ex.getClass().getSimpleName());
            close();
        }
    }

    private void close() {
        stop();
        try {
            session.close(CloseStatus.SESSION_NOT_RELIABLE);
        } catch (IOException | RuntimeException ignored) {
            // Already closing; the handler's afterConnectionClosed cleans up either way.
        }
    }

    private static void limitWriteTime(WebSocketSession session) {
        if (session instanceof NativeWebSocketSession nativeSession) {
            jakarta.websocket.Session jakartaSession = nativeSession.getNativeSession(jakarta.websocket.Session.class);
            if (jakartaSession != null) {
                jakartaSession.getUserProperties().put(TOMCAT_BLOCKING_SEND_TIMEOUT, SEND_TIMEOUT_MS);
            }
        }
    }
}
