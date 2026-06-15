package com.example.durakgame.service.store;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryGameReaperTest {

    private final InMemoryGameStore store = new InMemoryGameStore();
    private final InMemoryGameReaper reaper = new InMemoryGameReaper(store, 120, 24);

    @Test
    void keepsFreshLobby() {
        Game game = new Game("FRESH1", new Player("Host"));
        assertFalse(reaper.shouldReap(game, Instant.now().plus(5, ChronoUnit.MINUTES)));
    }

    @Test
    void reapsAbandonedLobbyPastLobbyMaxAge() {
        Game game = new Game("OLD001", new Player("Host"));
        Instant later = game.getCreatedAt().plus(3, ChronoUnit.HOURS);
        assertTrue(reaper.shouldReap(game, later));
    }

    @Test
    void keepsActiveGameUnderHardCeiling() {
        Game game = new Game("PLAY01", new Player("Host"));
        game.addPlayer("Guest", 4);
        game.start(game.getHostPlayerId());
        // In progress for 3h: past lobby age, but lobbies-only rule must not apply to it.
        Instant later = game.getCreatedAt().plus(3, ChronoUnit.HOURS);
        assertFalse(reaper.shouldReap(game, later));
    }

    @Test
    void reapsAnyGamePastHardCeiling() {
        Game game = new Game("PLAY02", new Player("Host"));
        game.addPlayer("Guest", 4);
        game.start(game.getHostPlayerId());
        Instant later = game.getCreatedAt().plus(25, ChronoUnit.HOURS);
        assertTrue(reaper.shouldReap(game, later));
    }

    @Test
    void reapSweepEvictsOnlyStaleGames() {
        store.save(new Game("LIVE01", new Player("Host")));        // fresh lobby, kept
        store.save(agedLobby("DEAD01", 3 * 60));                   // 3h-old lobby, reaped

        reaper.reap();

        assertTrue(store.existsByCode("LIVE01"));
        assertFalse(store.existsByCode("DEAD01"));
    }

    /** A LOBBY game whose createdAt is {@code minutesAgo} in the past, via snapshot reconstruction. */
    private static Game agedLobby(String code, long minutesAgo) {
        long createdAtMs = Instant.now().minus(minutesAgo, ChronoUnit.MINUTES).toEpochMilli();
        Game.PlayerSnapshot host = new Game.PlayerSnapshot("host", "Host", createdAtMs, false, null, List.of(), "");
        return Game.fromSnapshot(new Game.Snapshot(
                code, createdAtMs, "host", GameStatus.LOBBY,
                null, null, -1, -1, null, false, 0, 0L,
                List.of(host), List.of(), List.of(), Set.of(), List.of(), List.of()));
    }
}
