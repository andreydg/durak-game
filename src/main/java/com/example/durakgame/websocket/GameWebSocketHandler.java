package com.example.durakgame.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

    private final Map<String, Set<WebSocketSession>> gameSessions = new ConcurrentHashMap<>();
    /* Every write goes through the session's sender, so broadcasting never waits for a client. */
    private final Map<String, SessionSender> senders = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> botThinking = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final WebSocketAdmission admission;

    public GameWebSocketHandler(ObjectMapper objectMapper) {
        this(objectMapper, WebSocketAdmission.unlimited());
    }

    @Autowired
    public GameWebSocketHandler(ObjectMapper objectMapper, WebSocketAdmission admission) {
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
        String gameCode = gameCodeFromSession(session);
        try {
            senders.put(session.getId(), new SessionSender(session));
            gameSessions.computeIfAbsent(gameCode, ignored -> ConcurrentHashMap.newKeySet()).add(session);
            replayBotThinking(gameCode, session);
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

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        unregister(gameCodeFromSession(session), session);
        SessionSender sender = senders.remove(session.getId());
        if (sender != null) {
            sender.stop();
        }
        admission.release(session);
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

    /**
     * Stops broadcasting to a session that is closed or too far behind. Its sender closes it, and
     * afterConnectionClosed releases the rest once the connection is really gone.
     */
    private void unregister(String gameCode, WebSocketSession session) {
        String normalizedCode = normalizeCode(gameCode);
        Set<WebSocketSession> sessions = gameSessions.get(normalizedCode);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            gameSessions.remove(normalizedCode, sessions);
        }
    }

    public void broadcastGameUpdated(String gameCode) {
        broadcastGameUpdated(gameCode, null);
    }

    public void broadcastGameUpdated(String gameCode, Long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "GAME_UPDATED");
        if (version != null) {
            payload.put("version", version);
        }
        broadcast(normalizeCode(gameCode), toJson(payload));
    }

    public void broadcastBotThinking(String gameCode, String playerId, boolean thinking) {
        broadcastBotThinking(gameCode, playerId, thinking, thinking ? "thinking..." : "");
    }

    public void broadcastBotThinking(String gameCode, String playerId, boolean thinking, String message) {
        String normalizedCode = normalizeCode(gameCode);
        String resolvedPlayerId = playerId == null ? "" : playerId;
        long eventAtMs = System.currentTimeMillis();
        if (thinking) {
            botThinking.computeIfAbsent(normalizedCode, ignored -> new ConcurrentHashMap<>())
                    .put(resolvedPlayerId, message == null || message.isBlank() ? "thinking..." : message);
        } else {
            Map<String, String> gameThinking = botThinking.get(normalizedCode);
            if (gameThinking != null) {
                gameThinking.remove(resolvedPlayerId);
                if (gameThinking.isEmpty()) {
                    botThinking.remove(normalizedCode);
                }
            }
        }
        broadcast(normalizedCode, botThinkingJson(resolvedPlayerId, thinking, message == null ? "" : message, eventAtMs));
    }

    public Map<String, String> botThinkingForGame(String gameCode) {
        Map<String, String> gameThinking = botThinking.get(normalizeCode(gameCode));
        if (gameThinking == null || gameThinking.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(gameThinking);
    }

    private void replayBotThinking(String gameCode, WebSocketSession session) {
        Map<String, String> gameThinking = botThinking.get(normalizeCode(gameCode));
        if (gameThinking == null || gameThinking.isEmpty() || !session.isOpen()) {
            return;
        }
        for (Map.Entry<String, String> entry : gameThinking.entrySet()) {
            TextMessage message = new TextMessage(
                    botThinkingJson(entry.getKey(), true, entry.getValue(), System.currentTimeMillis()));
            if (!send(session, message)) {
                unregister(gameCode, session);
                return;
            }
        }
    }

    private String botThinkingJson(String playerId, boolean thinking, String message, long eventAtMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "BOT_THINKING");
        payload.put("playerId", playerId);
        payload.put("thinking", thinking);
        payload.put("message", message);
        payload.put("eventAtMs", eventAtMs);
        return toJson(payload);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize websocket payload", ex);
        }
    }

    private void broadcast(String gameCode, String payload) {
        Set<WebSocketSession> sessions = gameSessions.get(normalizeCode(gameCode));
        if (sessions == null) {
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (WebSocketSession session : sessions) {
            if (!send(session, message)) {
                unregister(gameCode, session);
            }
        }
    }

    /** Queues without blocking; false when the session is gone or too far behind (its sender closes it). */
    private boolean send(WebSocketSession session, TextMessage message) {
        SessionSender sender = senders.get(session.getId());
        return sender != null && sender.send(message);
    }

    private static String gameCodeFromSession(WebSocketSession session) {
        if (session.getUri() == null) {
            return "";
        }
        String path = session.getUri().getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == path.length() - 1) {
            return "";
        }
        return normalizeCode(path.substring(lastSlash + 1));
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
