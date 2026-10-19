package com.example.durakgame.websocket;

import com.example.durakgame.service.LobbyUpdatePublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broadcasts public-lobby invalidations. The payload deliberately contains no room data:
 * clients re-read the authoritative projection through {@code GET /api/lobbies}.
 */
@Component
public class LobbyWebSocketHandler extends TextWebSocketHandler implements LobbyUpdatePublisher {
    /** Bursts of changes within this window go out as one message carrying the latest revision. */
    static final long MIN_BROADCAST_INTERVAL_MS = 100;
    private static final Logger log = LoggerFactory.getLogger(LobbyWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    /* Every write goes through the session's sender, so publishing never waits for a client. */
    private final Map<String, SessionSender> senders = new ConcurrentHashMap<>();
    private final String streamId = UUID.randomUUID().toString();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong deliveredRevision = new AtomicLong();
    private final AtomicBoolean broadcastScheduled = new AtomicBoolean();
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("lobby-websocket-", 0).factory());
    private final ObjectMapper objectMapper;
    private final WebSocketAdmission admission;

    public LobbyWebSocketHandler(ObjectMapper objectMapper) {
        this(objectMapper, WebSocketAdmission.unlimited());
    }

    @Autowired
    public LobbyWebSocketHandler(ObjectMapper objectMapper, WebSocketAdmission admission) {
        this.objectMapper = objectMapper;
        this.admission = admission;
    }

    /** The handshake already reserved this socket's place in every cap (WebSocketAdmission). */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (!admission.opened(session)) {
            SessionSender.closeNow(session, CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        try {
            SessionSender sender = new SessionSender(session);
            senders.put(session.getId(), sender);
            sessions.add(session);
            if (!sender.send(message("LOBBIES_READY", revision.get()))) {
                sessions.remove(session);
            }
        } catch (RuntimeException ex) {
            // Thrown on, it would make Spring close the session the ordinary way (see handleBinaryMessage).
            log.warn("websocket_open_failed sessionId={}", session.getId(), ex);
            closeConnection(session, CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * Clients only listen, so a binary message ends the session. The inherited handling closes it
     * the ordinary way, which leaves the connection open until the client answers, while
     * afterConnectionClosed has already given back its place; this drops it after the close frame.
     */
    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        closeConnection(session, CloseStatus.NOT_ACCEPTABLE.withReason("Binary messages not supported"));
    }

    /** From the session's sender thread, or from this one (the connection's own) if it has no sender. */
    private void closeConnection(WebSocketSession session, CloseStatus status) {
        SessionSender sender = senders.get(session.getId());
        if (sender != null) {
            sender.close(status);
        } else {
            SessionSender.closeNow(session, status);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        SessionSender sender = senders.remove(session.getId());
        if (sender != null) {
            sender.stop();
        }
        admission.release(session);
    }

    @Override
    public void lobbiesChanged() {
        revision.incrementAndGet();
        scheduleBroadcast();
    }

    private void scheduleBroadcast() {
        if (!broadcastScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            broadcastExecutor.execute(this::drainBroadcasts);
        } catch (RejectedExecutionException ignored) {
            broadcastScheduled.set(false);
        }
    }

    /**
     * Coalesces bursts while retaining a monotonic revision that lets clients ignore stale deliveries.
     * Sending only queues, so a short pause after each broadcast is what lets a burst collapse: every
     * client refetches the lobby list per message.
     */
    private void drainBroadcasts() {
        long sentRevision = deliveredRevision.get();
        try {
            while (sentRevision != revision.get()) {
                sentRevision = revision.get();
                broadcast(message("LOBBIES_CHANGED", sentRevision));
                deliveredRevision.set(sentRevision);
                Thread.sleep(MIN_BROADCAST_INTERVAL_MS);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } finally {
            broadcastScheduled.set(false);
            if (revision.get() != deliveredRevision.get()) {
                scheduleBroadcast();
            }
        }
    }

    /**
     * A session that is closed or too far behind stops receiving broadcasts here. Its sender closes
     * it, and afterConnectionClosed releases the rest once the connection is really gone.
     */
    private void broadcast(TextMessage message) {
        for (WebSocketSession session : sessions) {
            SessionSender sender = senders.get(session.getId());
            if (sender == null || !sender.send(message)) {
                sessions.remove(session);
            }
        }
    }

    @PreDestroy
    void close() {
        broadcastExecutor.shutdownNow();
    }

    private TextMessage message(String type, long currentRevision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("streamId", streamId);
        payload.put("revision", currentRevision);
        try {
            return new TextMessage(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize lobby websocket payload", ex);
        }
    }
}
