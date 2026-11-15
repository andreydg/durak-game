package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryGameStoreTest {

    @Test
    void receiptRetryAfterHeartbeatDoesNotOverwriteNewerState() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(new Game("CODE01", new Player("Host")));
        Game attempt = store.findByCode("CODE01").orElseThrow();
        attempt.addPlayer("Guest", 4);
        Game unacknowledged = Game.fromSnapshot(attempt.toSnapshot());
        ReflectionTestUtils.setField(unacknowledged, "loadedVersion", attempt.getLoadedVersion());
        String receipt = UUID.randomUUID().toString();
        store.saveWithReceipt(attempt, receipt);
        assertEquals(attempt.getVersion(), attempt.getLoadedVersion());

        Game heartbeat = store.findByCode("CODE01").orElseThrow();
        heartbeat.markActive();
        store.save(heartbeat);
        assertTrue(store.hasWriteReceipt("CODE01", receipt));

        store.saveWithReceipt(unacknowledged, receipt);
        store.saveWithReceipt(unacknowledged, receipt);
        assertEquals(unacknowledged.getVersion(), unacknowledged.getLoadedVersion());
        assertEquals(heartbeat.toSnapshot(), store.findByCode("CODE01").orElseThrow().toSnapshot());
        // The receipt-aware API must not relax the existing plain-save CAS contract.
        assertThrows(StaleGameWriteException.class, () -> store.save(unacknowledged));
        unacknowledged.addPlayer("Not saved", 4);
        assertEquals(2, store.listAll().iterator().next().getPlayers().size());
    }

    @Test
    void realConflictDoesNotCreateReceiptOrPublishChanges() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(new Game("CODE01", new Player("Host")));
        Game stale = store.findByCode("CODE01").orElseThrow();
        Game winner = store.findByCode("CODE01").orElseThrow();
        winner.markActive();
        store.save(winner);
        stale.addPlayer("Rejected", 4);
        String receipt = UUID.randomUUID().toString();

        assertThrows(StaleGameWriteException.class, () -> store.saveWithReceipt(stale, receipt));
        assertFalse(store.hasWriteReceipt("CODE01", receipt));
        assertEquals(0, stale.getLoadedVersion());
        assertEquals(winner.toSnapshot(), store.findByCode("CODE01").orElseThrow().toSnapshot());
    }

    @Test
    void byteIdenticalCompetingWritesWithDifferentIdsAreStillConflicts() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game first = new Game("CODE01", new Player("Host"));
        store.save(first);
        first.markActive();
        Game identical = Game.fromSnapshot(first.toSnapshot());
        ReflectionTestUtils.setField(identical, "loadedVersion", first.getLoadedVersion());
        String firstReceipt = UUID.randomUUID().toString();
        String otherReceipt = UUID.randomUUID().toString();
        GameBinaryCodec codec = new GameBinaryCodec();
        assertArrayEquals(codec.encode(first), codec.encode(identical));

        store.saveWithReceipt(first, firstReceipt);
        assertThrows(StaleGameWriteException.class, () -> store.saveWithReceipt(identical, otherReceipt));
        assertTrue(store.hasWriteReceipt("CODE01", firstReceipt));
        assertFalse(store.hasWriteReceipt("CODE01", otherReceipt));
        assertFalse(store.hasWriteReceipt("OTHER1", firstReceipt));
    }

    @Test
    void deleteAndRecreateDoesNotInheritReceipts() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game old = new Game("CODE01", new Player("Old host"));
        old.addPlayer("Guest", 4); // v1, different from the replacement's v0.
        String receipt = UUID.randomUUID().toString();
        store.saveWithReceipt(old, receipt);
        store.deleteByCode("CODE01");
        assertFalse(store.hasWriteReceipt("CODE01", receipt));
        assertThrows(StaleGameWriteException.class, () -> store.saveWithReceipt(old, receipt));

        Game replacement = new Game("CODE01", new Player("New host"));
        store.save(replacement);
        assertFalse(store.hasWriteReceipt("CODE01", receipt));
        assertThrows(StaleGameWriteException.class, () -> store.saveWithReceipt(old, receipt));
        assertEquals(replacement.toSnapshot(), store.findByCode("CODE01").orElseThrow().toSnapshot());
    }

    @Test
    void conditionalCleanupRemovesReceiptsOnlyWhenItRemovesTheGame() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        String receipt = UUID.randomUUID().toString();
        store.saveWithReceipt(game, receipt);
        assertFalse(store.deleteIfVersion("CODE01", game.getVersion() + 1));
        assertTrue(store.hasWriteReceipt("CODE01", receipt));
        assertTrue(store.deleteIfVersion("CODE01", game.getVersion()));
        assertFalse(store.hasWriteReceipt("CODE01", receipt));
        store.save(new Game("CODE01", new Player("Replacement")));
        assertFalse(store.hasWriteReceipt("CODE01", receipt));
    }

    @Test
    void blankReceiptIdsCannotTurnReceiptWritesIntoPlainWrites() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        assertThrows(IllegalArgumentException.class, () -> store.saveWithReceipt(game, null));
        assertThrows(IllegalArgumentException.class, () -> store.saveWithReceipt(game, " "));
        assertFalse(store.existsByCode("CODE01"));
    }

    @Test
    void savesAndReadsBack() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        store.save(game);

        assertTrue(store.existsByCode("CODE01"));
        assertEquals("CODE01", store.findByCode("CODE01").orElseThrow().getCode());
    }

    @Test
    void readsReturnCopiesSoUnsavedChangesNeverLeak() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(new Game("CODE01", new Player("Host")));

        Game read = store.findByCode("CODE01").orElseThrow();
        read.addPlayer("Guest", 4);   // e.g. a request that fails before it saves

        assertEquals(1, store.findByCode("CODE01").orElseThrow().getPlayers().size());
        assertEquals(1, store.listAll().iterator().next().getPlayers().size());
    }

    @Test
    void savePublishesChangesAndRejectsAnotherCopyReadBeforeThem() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(new Game("CODE01", new Player("Host")));

        Game read = store.findByCode("CODE01").orElseThrow();
        Game concurrent = store.findByCode("CODE01").orElseThrow();
        read.addPlayer("Guest", 4);
        store.save(read);

        assertEquals(2, store.findByCode("CODE01").orElseThrow().getPlayers().size());
        concurrent.addPlayer("Other", 4); // the same version as the saved change, built on the state before it
        assertThrows(StaleGameWriteException.class, () -> store.save(concurrent));
        assertEquals(2, store.findByCode("CODE01").orElseThrow().getPlayers().size());
    }

    @Test
    void sameObjectCanBeSavedAgainAfterAnotherChange() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        store.save(game); // creates it

        // Each save moves the expected version to the one just written.
        game.addPlayer("Guest", 4);
        store.save(game);
        game.addPlayer("Third", 4);
        store.save(game);

        assertEquals(3, store.findByCode("CODE01").orElseThrow().getPlayers().size());
    }

    @Test
    void deleteRemovesGame() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(new Game("CODE01", new Player("Host")));
        store.deleteByCode("CODE01");

        assertFalse(store.existsByCode("CODE01"));
        assertTrue(store.findByCode("CODE01").isEmpty());
    }

    @Test
    void deleteIfVersionRemovesOnlyTheVersionItWasGiven() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        store.save(game);
        long seenVersion = game.getVersion();

        Game renewed = store.findByCode("CODE01").orElseThrow();
        renewed.markActive();   // e.g. a heartbeat saved after the version was read
        store.save(renewed);

        assertFalse(store.deleteIfVersion("CODE01", seenVersion));
        assertTrue(store.existsByCode("CODE01"));
        assertTrue(store.deleteIfVersion("CODE01", renewed.getVersion()));
        assertFalse(store.existsByCode("CODE01"));
        assertFalse(store.deleteIfVersion("CODE01", renewed.getVersion()));
    }

    @Test
    void rejectsStaleWriteForDistinctOlderCopy() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        game.addPlayer("Guest", 4); // version bump -> v1
        store.save(game);

        // A distinct copy decoded at v1 must not overwrite a save that landed after it was decoded.
        Game stale = Game.fromSnapshot(game.toSnapshot());
        game.markActive(); // v2
        store.save(game);

        stale.markActive(); // also v2, but built on v1
        assertThrows(StaleGameWriteException.class, () -> store.save(stale));
    }

    @Test
    void acceptsDistinctCopyDecodedAtTheStoredVersion() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        store.save(game);

        Game copy = Game.fromSnapshot(game.toSnapshot()); // expects the version it was decoded at
        copy.addPlayer("Guest", 4);
        store.save(copy);

        assertEquals(2, store.findByCode("CODE01").orElseThrow().getPlayers().size());
    }

    @Test
    void staleCopyCannotOverwriteANewerSaveEvenWithAHigherVersion() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        Player guest = game.addPlayer("Guest", 4);
        store.save(game); // v1

        Game copyA = store.findByCode("CODE01").orElseThrow();
        Game copyB = store.findByCode("CODE01").orElseThrow();
        copyB.removePlayerFromLobby(guest.getId()); // one change: the guest leaves (v2)
        store.save(copyB);

        copyA.addPlayer("Late", 4); // two changes on the stale copy (v3), past the stored v2
        copyA.markActive();
        assertThrows(StaleGameWriteException.class, () -> store.save(copyA));

        // B's change survives: the guest who left does not come back.
        assertEquals(List.of("Host"),
                store.findByCode("CODE01").orElseThrow().getPlayers().stream().map(Player::getName).toList());
    }

    @Test
    void staleSaveCannotResurrectADeletedGame() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(new Game("CODE01", new Player("Host")));
        Game read = store.findByCode("CODE01").orElseThrow();
        store.deleteByCode("CODE01"); // e.g. another writer removed the room

        read.addPlayer("Guest", 4);
        assertThrows(StaleGameWriteException.class, () -> store.save(read));
        assertFalse(store.existsByCode("CODE01"));
    }

    @Test
    void creatingAGameWhoseCodeIsTakenIsRejected() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(new Game("CODE01", new Player("Host")));

        Game other = new Game("CODE01", new Player("Other"));
        other.addPlayer("Guest", 4); // ahead of the stored version, so only the existence check stops it
        assertThrows(StaleGameWriteException.class, () -> store.save(other));
        assertEquals("Host", store.findByCode("CODE01").orElseThrow().getPlayers().getFirst().getName());
    }

    @Test
    void lobbySummariesCarryHostNameAndPlayerNames() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("LOBBY1", new Player("Alice"));
        game.addPlayer("Bob", 4);
        store.save(game);

        List<LobbyProjection> summaries = store.listOpenLobbySummaries();
        assertEquals(1, summaries.size());
        LobbyProjection p = summaries.getFirst();
        assertEquals("LOBBY1", p.code());
        assertEquals("Alice", p.hostName());
        assertEquals(2, p.playerCount());
        assertTrue(p.playerNames().containsAll(List.of("Alice", "Bob")));
    }

    @Test
    void listOpenLobbiesFiltersByLobbyStatus() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(new Game("LOBBY1", new Player("Host")));

        Game started = new Game("PLAY01", new Player("Host"));
        started.addPlayer("Guest", 4);
        started.start(started.getHostPlayerId());
        store.save(started);

        List<String> lobbyCodes = store.listOpenLobbies().stream().map(Game::getCode).toList();
        assertTrue(lobbyCodes.contains("LOBBY1"));
        assertFalse(lobbyCodes.contains("PLAY01"));
        assertEquals(GameStatus.IN_PROGRESS, store.findByCode("PLAY01").orElseThrow().getStatus());
    }
}
