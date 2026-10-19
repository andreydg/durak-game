package com.example.durakgame.websocket;

import jakarta.websocket.CloseReason;
import org.apache.tomcat.websocket.WsSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes one session's outgoing messages, in order, from the session's own virtual thread.
 * Broadcasters (HTTP requests, bot turns, the lobby publisher) only enqueue, so a slow or dead
 * client can never hold them up. The queue is bounded and every write has a deadline: a client
 * that falls that far behind, or stops reading, is closed and reconnects like any dropped client.
 * That close also happens on this thread. It writes a close frame, which can wait as long as any
 * other write, so a broadcaster that finds the queue full only marks the session for closing.
 */
final class SessionSender {
    static final int QUEUE_CAPACITY = 32;
    static final long SEND_TIMEOUT_MS = 5_000;
    /** Tomcat's per-session limit for a blocking write (20 seconds by default), read on every write. */
    private static final String TOMCAT_BLOCKING_SEND_TIMEOUT = "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT";
    private static final Logger log = LoggerFactory.getLogger(SessionSender.class);

    private final WebSocketSession session;
    private final Thread thread;
    /* Guards the fields below. Nothing that can wait on the client runs while holding it. */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<TextMessage> queue = new ArrayDeque<>(QUEUE_CAPACITY);
    /** Set once: this thread writes nothing more and closes the session with it. */
    private CloseStatus closing;
    /** The connection is gone: this thread just ends. */
    private boolean stopped;

    SessionSender(WebSocketSession session) {
        this.session = session;
        limitWriteTime(session);
        this.thread = Thread.ofVirtual().name("websocket-send-" + session.getId()).unstarted(this::run);
        thread.start();
    }

    /**
     * Queues a message without blocking. False once the session is closed, being closed, or too
     * far behind; the caller should stop sending to it, and this sender closes it.
     */
    boolean send(TextMessage message) {
        if (!session.isOpen()) {
            return false;
        }
        lock.lock();
        try {
            if (closing != null || stopped) {
                return false;
            }
            if (queue.size() < QUEUE_CAPACITY) {
                queue.addLast(message);
                changed.signal();
                return true;
            }
            // The backlog would arrive too late to matter; the client re-reads the game when it reconnects.
            requestClose(CloseStatus.SESSION_NOT_RELIABLE);
        } finally {
            lock.unlock();
        }
        log.info("websocket_session_dropped reason=send_queue_full sessionId={}", session.getId());
        return false;
    }

    /**
     * Closes the session from the sender's own thread, as a full queue does: anything still queued
     * is dropped, the write in flight finishes first, and the connection goes right after the close
     * frame ({@link #closeNow}). For closes the server decides on the connection's own events, such
     * as a message it doesn't accept. Returns at once; the first status wins.
     */
    void close(CloseStatus status) {
        lock.lock();
        try {
            requestClose(status);
        } finally {
            lock.unlock();
        }
    }

    /** The connection closed: drops queued messages and ends the thread. Safe to call more than once. */
    void stop() {
        lock.lock();
        try {
            stopped = true;
            queue.clear();
            changed.signal();
        } finally {
            lock.unlock();
        }
    }

    /** True once the thread has ended, after closing the session if it had to. */
    boolean isDone() {
        return !thread.isAlive();
    }

    private void run() {
        try {
            TextMessage next;
            while ((next = nextMessage()) != null && session.isOpen()) {
                session.sendMessage(next);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ex) {
            log.info("websocket_session_dropped reason=send_failed sessionId={} error={}",
                    session.getId(), ex.getClass().getSimpleName());
            markClosing();
        }
        CloseStatus status = closeStatus();
        if (status != null) {
            closeNow(session, status);
        }
    }

    /** The next message to write, or null once the session is being closed or is gone. */
    private TextMessage nextMessage() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty() && closing == null && !stopped) {
                changed.await();
            }
            return closing != null || stopped ? null : queue.pollFirst();
        } finally {
            lock.unlock();
        }
    }

    private void markClosing() {
        lock.lock();
        try {
            requestClose(CloseStatus.SESSION_NOT_RELIABLE);
        } finally {
            lock.unlock();
        }
    }

    /** Holds the lock. Writes nothing more and has the thread close with {@code status}, unless already closing. */
    private void requestClose(CloseStatus status) {
        queue.clear();
        if (closing == null && !stopped) {
            closing = status;
            changed.signal();
        }
    }

    /** The status to close with, or null when there is nothing to close. */
    private CloseStatus closeStatus() {
        lock.lock();
        try {
            return stopped ? null : closing;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes a session the server gave up on. Writes the close frame, which the write deadline
     * bounds, and on Tomcat drops the connection right after it: a plain close keeps the connection
     * open for up to 30 seconds waiting for the client's reply, and such a client may never send one.
     * Blocks for as long as that write, so call it from the session's own thread, not a broadcaster.
     */
    static void closeNow(WebSocketSession session, CloseStatus status) {
        try {
            if (tomcatSession(session) instanceof WsSession tomcat) {
                CloseReason reason = new CloseReason(
                        CloseReason.CloseCodes.getCloseCode(status.getCode()), status.getReason());
                tomcat.doClose(reason, reason, true);
            } else {
                session.close(status);
            }
        } catch (IOException | RuntimeException ignored) {
            // Already closing; the handler's afterConnectionClosed cleans up either way.
        }
    }

    private static jakarta.websocket.Session tomcatSession(WebSocketSession session) {
        return session instanceof NativeWebSocketSession nativeSession
                ? nativeSession.getNativeSession(jakarta.websocket.Session.class)
                : null;
    }

    private static void limitWriteTime(WebSocketSession session) {
        jakarta.websocket.Session tomcat = tomcatSession(session);
        if (tomcat != null) {
            tomcat.getUserProperties().put(TOMCAT_BLOCKING_SEND_TIMEOUT, SEND_TIMEOUT_MS);
        }
    }
}
