package com.example.durakgame.websocket;

import com.example.durakgame.service.LobbyUpdatePublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
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

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!admission.admit(session)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Too many connections"));
            return;
        }
        SessionSender sender = new SessionSender(session);
        senders.put(session.getId(), sender);
        sessions.add(session);
        if (!sender.send(message("LOBBIES_READY", revision.get()))) {
            remove(session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        remove(session);
    }

    private void remove(WebSocketSession session) {
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

    private void broadcast(TextMessage message) {
        for (WebSocketSession session : sessions) {
            SessionSender sender = senders.get(session.getId());
            if (sender == null || !sender.send(message)) {
                remove(session);
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
