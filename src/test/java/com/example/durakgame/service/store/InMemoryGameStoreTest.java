package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryGameStoreTest {

    @Test
    void savesAndReadsBack() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        store.save(game);

        assertTrue(store.existsByCode("CODE01"));
        assertEquals("CODE01", store.findByCode("CODE01").orElseThrow().getCode());
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
    void rejectsStaleWriteForDistinctOlderCopy() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        game.addPlayer("Guest", 4); // version bump -> v1
        store.save(game);

        // A distinct copy decoded at the same version must not overwrite.
        Game stale = Game.fromSnapshot(game.toSnapshot());
        assertThrows(StaleGameWriteException.class, () -> store.save(stale));
    }

    @Test
    void acceptsNewerVersionForDistinctCopy() {
        InMemoryGameStore store = new InMemoryGameStore();
        Game game = new Game("CODE01", new Player("Host"));
        store.save(game);

        Game newer = Game.fromSnapshot(game.toSnapshot());
        newer.addPlayer("Guest", 4); // advances version beyond stored
        store.save(newer);

        assertEquals(2, store.findByCode("CODE01").orElseThrow().getPlayers().size());
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
