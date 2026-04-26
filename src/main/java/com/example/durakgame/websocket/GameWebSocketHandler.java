package com.example.durakgame.websocket;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GameWebSocketHandler extends TextWebSocketHandler {
    private static final Map<String, Set<WebSocketSession>> GAME_SESSIONS = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String gameCode = gameCodeFromSession(session);
        GAME_SESSIONS.computeIfAbsent(gameCode, ignored -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String gameCode = gameCodeFromSession(session);
        Set<WebSocketSession> sessions = GAME_SESSIONS.get(gameCode);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            GAME_SESSIONS.remove(gameCode);
        }
    }

    public static void broadcastGameUpdated(String gameCode) {
        broadcastGameUpdated(gameCode, null);
    }

    public static void broadcastGameUpdated(String gameCode, Long version) {
        broadcast(normalizeCode(gameCode), version == null
                ? "{\"type\":\"GAME_UPDATED\"}"
                : "{\"type\":\"GAME_UPDATED\",\"version\":" + version + "}");
    }

    public static void broadcastBotThinking(String gameCode, String playerId, boolean thinking) {
        broadcastBotThinking(gameCode, playerId, thinking, thinking ? "thinking..." : "");
    }

    public static void broadcastBotThinking(String gameCode, String playerId, boolean thinking, String message) {
        String safePlayerId = playerId == null ? "" : playerId.replace("\\", "\\\\").replace("\"", "\\\"");
        String safeMessage = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        broadcast(normalizeCode(gameCode),
                "{\"type\":\"BOT_THINKING\",\"playerId\":\"" + safePlayerId + "\",\"thinking\":" + thinking
                        + ",\"message\":\"" + safeMessage + "\"}");
    }

    private static void broadcast(String gameCode, String payload) {
        Set<WebSocketSession> sessions = GAME_SESSIONS.get(normalizeCode(gameCode));
        if (sessions == null) {
            return;
        }
        TextMessage message = new TextMessage(payload);
        sessions.removeIf(session -> !session.isOpen());
        for (WebSocketSession session : sessions) {
            try {
                session.sendMessage(message);
            } catch (IOException ignored) {
                // Failed session will be cleaned by closed check.
            }
        }
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
