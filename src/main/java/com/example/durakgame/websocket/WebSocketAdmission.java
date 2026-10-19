package com.example.durakgame.websocket;

import com.example.durakgame.ratelimit.RateLimitFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounds the websockets one instance holds. Cloud Run counts every open socket against the
 * service's request concurrency, so unbounded sockets could starve ordinary requests.
 * <ul>
 *   <li>The handshake refuses a game socket for anything that cannot be a room code, so invented
 *   paths don't create registry entries.</li>
 *   <li>Before the upgrade, the handshake reserves a place in every cap at once: the instance
 *   total, the client address (read the way the rate limiter reads it), and for game sockets the
 *   room. Over any cap it answers with an HTTP error and closes the connection, so there is never
 *   an opened socket that only gets closed again, which a client could keep half-open.</li>
 *   <li>The reservation becomes the socket's and is released when the connection closes, or at
 *   once when the handshake does not upgrade (another check refused it, or it was not a valid
 *   upgrade). An upgraded connection that never opens loses it after {@link #OPEN_GRACE_SECONDS}.</li>
 * </ul>
 * Origins are checked by Spring, which allows only same-origin handshakes unless more are configured.
 */
@Component
public class WebSocketAdmission implements HandshakeInterceptor {
    static final String RESERVATION_ATTRIBUTE = WebSocketAdmission.class.getName() + ".reservation";
    static final long OPEN_GRACE_SECONDS = 30;
    private static final Pattern GAME_PATH = Pattern.compile("/ws/games/([^/]*)");
    /** Room codes are six characters from GameService.CODE_ALPHABET. */
    private static final Pattern ROOM_CODE = Pattern.compile("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}");
    private static final String UNKNOWN_CLIENT = "unknown";
    private static final Logger log = LoggerFactory.getLogger(WebSocketAdmission.class);

    private final int maxSessions;
    private final int maxSessionsPerClient;
    private final int maxSessionsPerGame;
    private final int forwardedForHops;
    private final LongSupplier nanoTime;
    /* Guarded by this. */
    private int reserved;
    private final Map<String, Integer> reservedPerClient = new HashMap<>();
    private final Map<String, Integer> reservedPerGame = new HashMap<>();
    private final Set<Reservation> notYetOpen = new HashSet<>();

    @Autowired
    public WebSocketAdmission(
            @Value("${app.websocket.max-sessions:150}") int maxSessions,
            @Value("${app.websocket.max-sessions-per-client:20}") int maxSessionsPerClient,
            @Value("${app.websocket.max-sessions-per-game:50}") int maxSessionsPerGame,
            @Value("${app.ratelimit.forwarded-for-hops:1}") int forwardedForHops
    ) {
        this(maxSessions, maxSessionsPerClient, maxSessionsPerGame, forwardedForHops, System::nanoTime);
    }

    WebSocketAdmission(int maxSessions, int maxSessionsPerClient, int maxSessionsPerGame, int forwardedForHops,
                       LongSupplier nanoTime) {
        this.maxSessions = maxSessions;
        this.maxSessionsPerClient = maxSessionsPerClient;
        this.maxSessionsPerGame = maxSessionsPerGame;
        this.forwardedForHops = forwardedForHops;
        this.nanoTime = nanoTime;
    }

    /** No caps; for handlers constructed outside Spring (tests). */
    static WebSocketAdmission unlimited() {
        return new WebSocketAdmission(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 1);
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String game = null;
        Matcher gamePath = GAME_PATH.matcher(request.getURI().getPath());
        if (gamePath.matches()) {
            game = gamePath.group(1).toUpperCase(Locale.ROOT);
            if (!ROOM_CODE.matcher(game).matches()) {
                response.setStatusCode(HttpStatus.NOT_FOUND);
                return false;
            }
        }
        String client = request instanceof ServletServerHttpRequest servletRequest
                ? RateLimitFilter.clientKeyOf(servletRequest.getServletRequest(), forwardedForHops)
                : UNKNOWN_CLIENT;
        Reservation reservation = new Reservation(client, game, nanoTime.getAsLong());
        Limit full = reserve(reservation);
        if (full != null) {
            log.info("websocket_rejected reason={} openSessions={}", full.reason, openSessions());
            response.setStatusCode(full.status);
            // Nothing else will be sent on this connection; don't let the client keep it open.
            response.getHeaders().set(HttpHeaders.CONNECTION, "close");
            return false;
        }
        attributes.put(RESERVATION_ATTRIBUTE, reservation);
        // afterHandshake sees only the request.
        request.getAttributes().put(RESERVATION_ATTRIBUTE, reservation);
        return true;
    }

    /**
     * Spring calls this whether or not the upgrade happened, including when a later interceptor
     * (the origin check) or the upgrade itself refused the request, and then without an exception.
     * The response status tells: only an upgrade answers 101.
     */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (request.getAttributes().get(RESERVATION_ATTRIBUTE) instanceof Reservation reservation
                && (exception != null || !upgraded(response))) {
            release(reservation);
        }
    }

    /**
     * Hands the handshake's reservation to the open socket. False when it has been released in the
     * meantime, which only happens if opening took longer than the grace period: the caller should
     * close the session. A session that did not come through this interceptor (a handler used
     * directly, as in tests) has nothing to hand over and is accepted.
     */
    synchronized boolean opened(WebSocketSession session) {
        if (!(session.getAttributes().get(RESERVATION_ATTRIBUTE) instanceof Reservation reservation)) {
            return true;
        }
        if (!reservation.held) {
            return false;
        }
        notYetOpen.remove(reservation);
        return true;
    }

    /** Releases the socket's reservation once its connection has closed. Safe to call more than once. */
    void release(WebSocketSession session) {
        if (session.getAttributes().get(RESERVATION_ATTRIBUTE) instanceof Reservation reservation) {
            release(reservation);
        }
    }

    /** Reserved places: open sockets plus handshakes in progress. */
    synchronized int openSessions() {
        releaseExpired();
        return reserved;
    }

    /** Takes a place in every cap that applies, or none: null when reserved, else the cap that is full. */
    private synchronized Limit reserve(Reservation reservation) {
        releaseExpired();
        if (reserved >= maxSessions) {
            return Limit.TOTAL;
        }
        if (reservedPerClient.getOrDefault(reservation.client, 0) >= maxSessionsPerClient) {
            return Limit.CLIENT;
        }
        if (reservation.game != null && reservedPerGame.getOrDefault(reservation.game, 0) >= maxSessionsPerGame) {
            return Limit.GAME;
        }
        reservation.held = true;
        reserved++;
        reservedPerClient.merge(reservation.client, 1, Integer::sum);
        if (reservation.game != null) {
            reservedPerGame.merge(reservation.game, 1, Integer::sum);
        }
        notYetOpen.add(reservation);
        return null;
    }

    private synchronized void release(Reservation reservation) {
        if (!reservation.held) {
            return;
        }
        reservation.held = false;
        notYetOpen.remove(reservation);
        reserved--;
        reservedPerClient.computeIfPresent(reservation.client, (client, count) -> count > 1 ? count - 1 : null);
        if (reservation.game != null) {
            reservedPerGame.computeIfPresent(reservation.game, (game, count) -> count > 1 ? count - 1 : null);
        }
    }

    /** Frees places held by handshakes that upgraded but never became an open socket. Holds the lock. */
    private void releaseExpired() {
        long now = nanoTime.getAsLong();
        long grace = TimeUnit.SECONDS.toNanos(OPEN_GRACE_SECONDS);
        List<Reservation> expired = notYetOpen.stream()
                .filter(reservation -> now - reservation.reservedAtNanos > grace)
                .toList();
        for (Reservation reservation : expired) {
            release(reservation);
            log.info("websocket_reservation_expired openSessions={}", reserved);
        }
    }

    private static boolean upgraded(ServerHttpResponse response) {
        return response instanceof ServletServerHttpResponse servletResponse
                && servletResponse.getServletResponse().getStatus() == HttpStatus.SWITCHING_PROTOCOLS.value();
    }

    private enum Limit {
        TOTAL(HttpStatus.SERVICE_UNAVAILABLE, "total_limit"),
        CLIENT(HttpStatus.TOO_MANY_REQUESTS, "client_limit"),
        GAME(HttpStatus.TOO_MANY_REQUESTS, "game_limit");

        private final HttpStatus status;
        private final String reason;

        Limit(HttpStatus status, String reason) {
            this.status = status;
            this.reason = reason;
        }
    }

    /** One socket's place in the caps, from the handshake until its connection closes. */
    static final class Reservation {
        private final String client;
        private final String game;
        private final long reservedAtNanos;
        /** Counted in the caps. Guarded by the admission. */
        private boolean held;

        private Reservation(String client, String game, long reservedAtNanos) {
            this.client = client;
            this.game = game;
            this.reservedAtNanos = reservedAtNanos;
        }

        String client() {
            return client;
        }
    }
}
