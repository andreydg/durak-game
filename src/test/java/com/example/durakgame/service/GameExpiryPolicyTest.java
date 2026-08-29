package com.example.durakgame.service;

import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Game;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameExpiryPolicyTest {
    private final GameExpiryPolicy policy = new GameExpiryPolicy(30, 24, 60);
    private final Instant activity = Instant.parse("2026-08-28T12:00:00Z");

    @Test
    void usesStatusSpecificDeadlines() {
        assertEquals(Instant.parse("2026-08-28T12:30:00Z"),
                policy.expiresAt(GameStatus.LOBBY, activity));
        assertEquals(Instant.parse("2026-08-29T12:00:00Z"),
                policy.expiresAt(GameStatus.IN_PROGRESS, activity));
        assertEquals(Instant.parse("2026-08-28T13:00:00Z"),
                policy.expiresAt(GameStatus.FINISHED, activity));
    }

    @Test
    void deadlineIsExclusive() {
        assertFalse(policy.isExpired(GameStatus.LOBBY, activity,
                Instant.parse("2026-08-28T12:29:59Z")));
        assertTrue(policy.isExpired(GameStatus.LOBBY, activity,
                Instant.parse("2026-08-28T12:30:00Z")));
    }

    @Test
    void lobbyCannotBeKeptAlivePastAbsoluteMaximumAge() {
        Instant createdAt = Instant.parse("2026-08-28T12:00:00Z");
        Instant recentHeartbeat = Instant.parse("2026-08-28T13:59:00Z");
        String hostId = "host";
        Game game = Game.fromSnapshot(new Game.Snapshot(
                "OLD001", createdAt.toEpochMilli(), recentHeartbeat.toEpochMilli(), hostId, GameStatus.LOBBY,
                null, null, -1, -1, null, false, 0, 0L,
                List.of(new Game.PlayerSnapshot(hostId, "Host", createdAt.toEpochMilli(),
                        false, null, List.of(), "secret")),
                List.of(), List.of(), Set.of(), List.of(), List.of()
        ));

        assertEquals(Instant.parse("2026-08-28T14:00:00Z"), policy.expiresAt(game));
        assertTrue(policy.isExpired(game, Instant.parse("2026-08-28T14:00:00Z")));
    }

    @Test
    void returningToLobbyAfterPlayStartsANewMaximumAgeWindow() {
        Instant oldCreation = Instant.now().minus(3, ChronoUnit.HOURS);
        String hostId = "host";
        String guestId = "guest";
        Game game = Game.fromSnapshot(new Game.Snapshot(
                "OLDPLY", oldCreation.toEpochMilli(), oldCreation.toEpochMilli(), hostId,
                GameStatus.IN_PROGRESS, null, null, 0, 1, null, false, 0, 7L,
                List.of(
                        new Game.PlayerSnapshot(hostId, "Host", oldCreation.toEpochMilli(),
                                false, null, List.of(), "host-secret"),
                        new Game.PlayerSnapshot(guestId, "Guest", oldCreation.toEpochMilli(),
                                false, null, List.of(), "guest-secret")
                ),
                List.of(), List.of(), Set.of(), List.of(), List.of()
        ));

        assertTrue(game.removePlayerAndResetToLobby(guestId));

        Instant now = Instant.now();
        assertEquals(GameStatus.LOBBY, game.getStatus());
        assertTrue(game.getLobbyStartedAt().isAfter(oldCreation.plus(2, ChronoUnit.HOURS)));
        assertFalse(policy.isExpired(game, now));
        assertTrue(policy.expiresAt(game).isAfter(now.plus(29, ChronoUnit.MINUTES)));
    }

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new GameExpiryPolicy(0, 24, 60));
        assertThrows(IllegalArgumentException.class, () -> new GameExpiryPolicy(30, 0, 24, 60));
        assertThrows(IllegalArgumentException.class, () -> new GameExpiryPolicy(30, -1, 60));
        assertThrows(IllegalArgumentException.class, () -> new GameExpiryPolicy(30, 24, 0));
    }
}
