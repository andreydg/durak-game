package com.example.durakgame.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {
    /** Bounds connection resources per room (well above any legitimate player/spectator count). */
    private static final int MAX_SESSIONS_PER_GAME = 50;

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

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!admission.admit(session)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Too many connections"));
            return;
        }
        String gameCode = gameCodeFromSession(session);
        Set<WebSocketSession> sessions = gameSessions.computeIfAbsent(gameCode, ignored -> ConcurrentHashMap.newKeySet());
        if (sessions.size() >= MAX_SESSIONS_PER_GAME) {
            admission.release(session);
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Too many connections for this game"));
            return;
        }
        senders.put(session.getId(), new SessionSender(session));
        sessions.add(session);
        replayBotThinking(gameCode, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(gameCodeFromSession(session), session);
    }

    private void removeSession(String gameCode, WebSocketSession session) {
        SessionSender sender = senders.remove(session.getId());
        if (sender != null) {
            sender.stop();
        }
        admission.release(session);
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
                removeSession(gameCode, session);
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
                removeSession(gameCode, session);
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
