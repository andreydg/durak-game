package com.example.durakgame.service.store;

import com.example.durakgame.health.GameStoreHealthIndicator;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real Firestore store (transactional compare-and-swap, codec round-trip,
 * denormalized lobby projection) against the Firestore emulator. Skipped unless
 * FIRESTORE_EMULATOR_HOST is set, so it only runs where the emulator is available (the dedicated
 * CI job, or locally via {@code gcloud beta emulators firestore start}).
 */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class FirestoreGameStoreEmulatorTest {

    private final FirestoreGameStore store = new FirestoreGameStore("(default)");

    private String uniqueCode() {
        return "E" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    }

    @Test
    void receiptRetryAfterHeartbeatIsIdempotentWithoutRelaxingPlainSaveCas() {
        String code = uniqueCode();
        String receipt = UUID.randomUUID().toString();
        try {
            store.save(new Game(code, new Player("Host")));
            Game attempt = store.findByCode(code).orElseThrow();
            attempt.addPlayer("Guest", 4);
            Game unacknowledged = Game.fromSnapshot(attempt.toSnapshot());
            ReflectionTestUtils.setField(unacknowledged, "loadedVersion", attempt.getLoadedVersion());
            store.saveWithReceipt(attempt, receipt);

            Game heartbeat = store.findByCode(code).orElseThrow();
            heartbeat.markActive();
            store.save(heartbeat);
            assertTrue(store.hasWriteReceipt(code, receipt));

            store.saveWithReceipt(unacknowledged, receipt);
            store.saveWithReceipt(unacknowledged, receipt);
            assertEquals(unacknowledged.getVersion(), unacknowledged.getLoadedVersion());
            assertEquals(heartbeat.toSnapshot(), store.findByCode(code).orElseThrow().toSnapshot());
            assertThrows(StaleGameWriteException.class, () -> store.save(unacknowledged));
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void byteIdenticalCompetingWriteDoesNotAcquireTheWinnersReceipt() {
        String code = uniqueCode();
        String firstReceipt = UUID.randomUUID().toString();
        String otherReceipt = UUID.randomUUID().toString();
        try {
            Game first = new Game(code, new Player("Host"));
            store.save(first);
            first.markActive();
            Game identical = Game.fromSnapshot(first.toSnapshot());
            ReflectionTestUtils.setField(identical, "loadedVersion", first.getLoadedVersion());
            GameBinaryCodec codec = new GameBinaryCodec();
            assertArrayEquals(codec.encode(first), codec.encode(identical));

            store.saveWithReceipt(first, firstReceipt);

            assertThrows(StaleGameWriteException.class, () -> store.saveWithReceipt(identical, otherReceipt));
            assertTrue(store.hasWriteReceipt(code, firstReceipt));
            assertFalse(store.hasWriteReceipt(code, otherReceipt));
            assertEquals(first.toSnapshot(), store.findByCode(code).orElseThrow().toSnapshot());
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void deleteAndRecreateClearsThePreviousRoomsReceipts() {
        String code = uniqueCode();
        String receipt = UUID.randomUUID().toString();
        try {
            Game old = new Game(code, new Player("Old host"));
            old.addPlayer("Guest", 4);
            store.saveWithReceipt(old, receipt);
            store.deleteByCode(code);
            assertFalse(store.hasWriteReceipt(code, receipt));
            assertThrows(StaleGameWriteException.class, () -> store.saveWithReceipt(old, receipt));

            Game replacement = new Game(code, new Player("New host"));
            store.save(replacement);
            assertFalse(store.hasWriteReceipt(code, receipt));
            assertThrows(StaleGameWriteException.class, () -> store.saveWithReceipt(old, receipt));
            assertEquals(replacement.toSnapshot(), store.findByCode(code).orElseThrow().toSnapshot());
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void legacyDocumentsAndWriteTimeReceiptCleanupAreSupported() throws Exception {
        String code = uniqueCode();
        String receipt = UUID.randomUUID().toString();
        try {
            store.save(new Game(code, new Player("Host")));
            Firestore firestore = (Firestore) ReflectionTestUtils.getField(store, "firestore");
            var document = firestore.collection("games").document(code);
            document.update("writeReceipts", FieldValue.delete()).get();
            assertFalse(store.hasWriteReceipt(code, receipt));

            Game attempt = store.findByCode(code).orElseThrow();
            attempt.markActive();
            store.saveWithReceipt(attempt, receipt);
            assertTrue(store.hasWriteReceipt(code, receipt));

            long now = System.currentTimeMillis();
            String expired = UUID.randomUUID().toString();
            document.update("writeReceipts", Map.of(
                    receipt, now,
                    expired, now - WriteReceipts.RETENTION_MS - 1)).get();
            assertTrue(store.hasWriteReceipt(code, expired), "an unpruned receipt still proves ownership");

            Game heartbeat = store.findByCode(code).orElseThrow();
            heartbeat.markActive();
            store.save(heartbeat);
            assertTrue(store.hasWriteReceipt(code, receipt), "plain saves retain unexpired receipts");
            assertFalse(store.hasWriteReceipt(code, expired), "plain saves also clean up old receipts");
        } finally {
            store.deleteByCode(code);
        }
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
    void staleCopyCannotOverwriteANewerSaveEvenWithAHigherVersion() {
        String code = uniqueCode();
        Game game = new Game(code, new Player("Host"));
        Player guest = game.addPlayer("Guest", 4);
        try {
            store.save(game); // v1

            Game copyA = store.findByCode(code).orElseThrow();
            Game copyB = store.findByCode(code).orElseThrow();
            copyB.removePlayerFromLobby(guest.getId()); // one change: the guest leaves (v2)
            store.save(copyB);

            copyA.addPlayer("Late", 4); // two changes on the stale copy (v3), past the stored v2
            copyA.markActive();
            assertThrows(StaleGameWriteException.class, () -> store.save(copyA));

            // B's change survives: the guest who left does not come back.
            assertEquals(List.of("Host"),
                    store.findByCode(code).orElseThrow().getPlayers().stream().map(Player::getName).toList());
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void staleSaveCannotResurrectADeletedGame() {
        String code = uniqueCode();
        try {
            store.save(new Game(code, new Player("Host")));
            Game read = store.findByCode(code).orElseThrow();
            store.deleteByCode(code); // e.g. another writer removed the room

            read.addPlayer("Guest", 4);
            assertThrows(StaleGameWriteException.class, () -> store.save(read));
            assertFalse(store.existsByCode(code));
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void lobbySummariesUseDenormalizedFields() {
        String code = uniqueCode();
        Game game = new Game(code, new Player("Alice"), false);
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
            assertFalse(summary.publicRoom());
        } finally {
            store.deleteByCode(code);
        }
    }

    @Test
    void expiredLobbyIsHiddenButNotDeleted() {
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
        game.markNotPersisted(); // seeded as a new room, not a copy of a stored one
        try {
            store.save(game);

            assertFalse(store.listOpenLobbySummaries().stream().anyMatch(p -> p.code().equals(code)));
            // The read only hides it; the TTL policy on expireAt does the storage cleanup.
            assertTrue(store.existsByCode(code));
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
