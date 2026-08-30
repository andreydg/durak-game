package com.example.durakgame.websocket;

import com.example.durakgame.service.LobbyUpdatePublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
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
    private static final int MAX_SESSIONS = 500;

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final String streamId = UUID.randomUUID().toString();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong deliveredRevision = new AtomicLong();
    private final AtomicBoolean broadcastScheduled = new AtomicBoolean();
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("lobby-websocket-", 0).factory());
    private final ObjectMapper objectMapper;

    public LobbyWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        synchronized (sessions) {
            if (sessions.size() >= MAX_SESSIONS) {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("Too many lobby connections"));
                return;
            }
            sessions.add(session);
        }
        if (!sendSafely(session, message("LOBBIES_READY", revision.get()))) {
            sessions.remove(session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
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

    /** Coalesces bursts while retaining a monotonic revision that lets clients ignore stale deliveries. */
    private void drainBroadcasts() {
        long sentRevision = deliveredRevision.get();
        try {
            while (sentRevision != revision.get()) {
                sentRevision = revision.get();
                broadcast(message("LOBBIES_CHANGED", sentRevision));
                deliveredRevision.set(sentRevision);
            }
        } finally {
            broadcastScheduled.set(false);
            if (revision.get() != deliveredRevision.get()) {
                scheduleBroadcast();
            }
        }
    }

    private void broadcast(TextMessage message) {
        sessions.removeIf(session -> !session.isOpen());
        for (WebSocketSession session : sessions) {
            if (!sendSafely(session, message)) {
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

    /** Spring websocket sessions reject overlapping writes, so serialize per connection. */
    private boolean sendSafely(WebSocketSession session, TextMessage message) {
        try {
            synchronized (session) {
                if (!session.isOpen()) {
                    return false;
                }
                session.sendMessage(message);
            }
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }
}
