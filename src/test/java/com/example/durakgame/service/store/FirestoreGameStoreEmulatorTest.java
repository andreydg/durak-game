package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.UUID;

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
        } finally {
            store.deleteByCode(code);
        }
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
