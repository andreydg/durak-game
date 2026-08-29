package com.example.durakgame.service.store;

import com.example.durakgame.health.GameStoreHealthIndicator;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.health.contributor.Status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real Firestore store (transaction stale-check, codec round-trip, denormalized
 * lobby projection) against the Firestore emulator. Skipped unless FIRESTORE_EMULATOR_HOST is
 * set, so it only runs where the emulator is available (the dedicated CI job, or locally via
 * {@code gcloud beta emulators firestore start}).
 */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class FirestoreGameStoreEmulatorTest {

    private final FirestoreGameStore store = new FirestoreGameStore("(default)");

    private String uniqueCode() {
        return "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    }

    @Test
    void saveAndFindRoundTripsIncludingPlayerSecret() {
        String code = uniqueCode();
        Game game = new Game(code, new Player("Alice"));
        game.addPlayer("Bob", 4);
        String hostSecret = game.getPlayers().getFirst().getSecret();
        try {
            store.save(game);

            Game found = store.findByCode(code).orElseThrow();
            assertEquals(code, found.getCode());
            assertEquals(2, found.getPlayers().size());
            assertEquals(hostSecret, found.getPlayers().getFirst().getSecret());
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void rejectsStaleWriteAtOrBelowStoredVersion() {
        String code = uniqueCode();
        Game game = new Game(code, new Player("Host"));
        game.addPlayer("Guest", 4); // advance the version
        try {
            store.save(game);

            // A second, distinct copy at the same version must not overwrite.
            Game stale = Game.fromSnapshot(game.toSnapshot());
            assertThrows(StaleGameWriteException.class, () -> store.save(stale));
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void lobbySummariesUseDenormalizedFields() {
        String code = uniqueCode();
        Game game = new Game(code, new Player("Alice"));
        game.addPlayer("Bob", 4);
        try {
            store.save(game);

            LobbyProjection summary = store.listOpenLobbySummaries().stream()
                    .filter(p -> p.code().equals(code))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Alice", summary.hostName());
            assertEquals(2, summary.playerCount());
            assertTrue(summary.playerNames().containsAll(List.of("Alice", "Bob")));
            assertEquals(game.getLastActivityAt().toEpochMilli(), summary.lastActivityAt().toEpochMilli());
            assertEquals(game.getLobbyStartedAt().toEpochMilli(), summary.lobbyStartedAt().toEpochMilli());
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void expiredLobbyIsHiddenAndScheduledForCleanup() {
        String code = uniqueCode();
        Instant activity = Instant.now().minus(31, ChronoUnit.MINUTES);
        long timestamp = activity.toEpochMilli();
        Game.PlayerSnapshot host = new Game.PlayerSnapshot(
                "host", "Host", timestamp, false, null, List.of(), "secret");
        Game game = Game.fromSnapshot(new Game.Snapshot(
                code, timestamp, timestamp, "host", com.example.durakgame.model.GameStatus.LOBBY,
                null, null, -1, -1, null, false, 0, 0L,
                List.of(host), List.of(), List.of(), Set.of(), List.of(), List.of()
        ));
        try {
            store.save(game);

            assertFalse(store.listOpenLobbySummaries().stream().anyMatch(p -> p.code().equals(code)));
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void healthIndicatorReportsUpAgainstRealFirestore() {
        // Guards against using a reserved "__.*__" probe id, which Firestore rejects on read.
        GameStoreHealthIndicator indicator = new GameStoreHealthIndicator(store);
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void existsAndDeleteBehaveConsistently() {
        String code = uniqueCode();
        store.save(new Game(code, new Player("Host")));
        assertTrue(store.existsByCode(code));

        store.deleteByCode(code);
        assertFalse(store.existsByCode(code));
        assertTrue(store.findByCode(code).isEmpty());
    }
}
