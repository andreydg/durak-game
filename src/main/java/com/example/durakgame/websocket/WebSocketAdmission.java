package com.example.durakgame.websocket;

import com.example.durakgame.ratelimit.RateLimitFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounds the websockets one instance holds. Cloud Run counts every open socket against the
 * service's request concurrency, so unbounded sockets could starve ordinary requests.
 * <ul>
 *   <li>The handshake refuses a game socket for anything that cannot be a room code, so invented
 *   paths don't create registry entries. It also records the client address, read the way the
 *   rate limiter reads it.</li>
 *   <li>Established sessions count against a total cap and a per-address cap; handlers close the
 *   ones over either cap.</li>
 * </ul>
 * Origins are checked by Spring, which allows only same-origin handshakes unless more are configured.
 */
@Component
public class WebSocketAdmission implements HandshakeInterceptor {
    static final String CLIENT_KEY_ATTRIBUTE = WebSocketAdmission.class.getName() + ".clientKey";
    private static final Pattern GAME_PATH = Pattern.compile("/ws/games/([^/]*)");
    /** Room codes are six characters from GameService.CODE_ALPHABET. */
    private static final Pattern ROOM_CODE = Pattern.compile("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}");
    private static final String UNKNOWN_CLIENT = "unknown";
    private static final Logger log = LoggerFactory.getLogger(WebSocketAdmission.class);

    private final int maxSessions;
    private final int maxSessionsPerClient;
    private final int forwardedForHops;
    private final Set<String> admittedSessionIds = new HashSet<>();
    private final Map<String, Integer> sessionsPerClient = new HashMap<>();

    @Autowired
    public WebSocketAdmission(
            @Value("${app.websocket.max-sessions:150}") int maxSessions,
            @Value("${app.websocket.max-sessions-per-client:20}") int maxSessionsPerClient,
            @Value("${app.ratelimit.forwarded-for-hops:1}") int forwardedForHops
    ) {
        this.maxSessions = maxSessions;
        this.maxSessionsPerClient = maxSessionsPerClient;
        this.forwardedForHops = forwardedForHops;
    }

    /** No caps; for handlers constructed outside Spring (tests). */
    static WebSocketAdmission unlimited() {
        return new WebSocketAdmission(Integer.MAX_VALUE, Integer.MAX_VALUE, 1);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Matcher game = GAME_PATH.matcher(request.getURI().getPath());
        if (game.matches() && !ROOM_CODE.matcher(game.group(1).toUpperCase(Locale.ROOT)).matches()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
        attributes.put(CLIENT_KEY_ATTRIBUTE, request instanceof ServletServerHttpRequest servletRequest
                ? RateLimitFilter.clientKeyOf(servletRequest.getServletRequest(), forwardedForHops)
                : UNKNOWN_CLIENT);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    /** Counts an established session, or answers false when a cap is reached. */
    synchronized boolean admit(WebSocketSession session) {
        String client = clientKey(session);
        int fromClient = sessionsPerClient.getOrDefault(client, 0);
        if (admittedSessionIds.size() >= maxSessions || fromClient >= maxSessionsPerClient) {
            log.info("websocket_rejected reason={} openSessions={}",
                    fromClient >= maxSessionsPerClient ? "client_limit" : "total_limit", admittedSessionIds.size());
            return false;
        }
        if (admittedSessionIds.add(session.getId())) {
            sessionsPerClient.put(client, fromClient + 1);
        }
        return true;
    }

    /** Releases an admitted session's slots; safe to call more than once or for unadmitted sessions. */
    synchronized void release(WebSocketSession session) {
        if (!admittedSessionIds.remove(session.getId())) {
            return;
        }
        sessionsPerClient.computeIfPresent(clientKey(session), (client, count) -> count > 1 ? count - 1 : null);
    }

    synchronized int openSessions() {
        return admittedSessionIds.size();
    }

    private static String clientKey(WebSocketSession session) {
        Object key = session.getAttributes().get(CLIENT_KEY_ATTRIBUTE);
        return key instanceof String client ? client : UNKNOWN_CLIENT;
    }
}
