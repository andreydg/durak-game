package com.example.durakgame.service;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Central inactivity policy used by API reads, lobby listings, reapers, and Firestore TTL. */
@Component
public class GameExpiryPolicy {
    public static final long DEFAULT_LOBBY_IDLE_MINUTES = 30;
    public static final long DEFAULT_ACTIVE_IDLE_HOURS = 24;
    public static final long DEFAULT_FINISHED_RETENTION_MINUTES = 60;

    private final Duration lobbyIdle;
    private final Duration activeIdle;
    private final Duration finishedRetention;

    public GameExpiryPolicy(
            @Value("${app.expiry.lobby-idle-minutes:30}") long lobbyIdleMinutes,
            @Value("${app.expiry.active-idle-hours:24}") long activeIdleHours,
            @Value("${app.expiry.finished-retention-minutes:60}") long finishedRetentionMinutes
    ) {
        this.lobbyIdle = positive(Duration.ofMinutes(lobbyIdleMinutes), "lobby idle timeout");
        this.activeIdle = positive(Duration.ofHours(activeIdleHours), "active game idle timeout");
        this.finishedRetention = positive(Duration.ofMinutes(finishedRetentionMinutes), "finished retention");
    }

    public static GameExpiryPolicy defaults() {
        return new GameExpiryPolicy(
                DEFAULT_LOBBY_IDLE_MINUTES,
                DEFAULT_ACTIVE_IDLE_HOURS,
                DEFAULT_FINISHED_RETENTION_MINUTES
        );
    }

    public Instant expiresAt(Game game) {
        return expiresAt(game.getStatus(), game.getLastActivityAt());
    }

    public Instant expiresAt(GameStatus status, Instant lastActivityAt) {
        Instant base = lastActivityAt == null ? Instant.EPOCH : lastActivityAt;
        return base.plus(timeoutFor(status));
    }

    public boolean isExpired(Game game, Instant now) {
        return isExpired(game.getStatus(), game.getLastActivityAt(), now);
    }

    public boolean isExpired(GameStatus status, Instant lastActivityAt, Instant now) {
        return !expiresAt(status, lastActivityAt).isAfter(now);
    }

    private Duration timeoutFor(GameStatus status) {
        return switch (status) {
            case LOBBY -> lobbyIdle;
            case IN_PROGRESS -> activeIdle;
            case FINISHED -> finishedRetention;
        };
    }

    private static Duration positive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
