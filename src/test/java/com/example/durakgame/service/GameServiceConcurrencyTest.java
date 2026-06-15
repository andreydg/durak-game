package com.example.durakgame.service;

import com.example.durakgame.model.Game;
import com.example.durakgame.model.Player;
import com.example.durakgame.service.autoplay.AutoPlayDecisionEngine;
import com.example.durakgame.service.store.InMemoryGameStore;
import com.example.durakgame.websocket.GameWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameServiceConcurrencyTest {

    private final AutoPlayDecisionEngine noOpEngine = (game, playerId, legalMoves) -> null;
    private final GameWebSocketHandler webSocket = new GameWebSocketHandler(new ObjectMapper());

    private GameService newService() {
        return new GameService(new InMemoryGameStore(), noOpEngine, webSocket);
    }

    @Test
    void concurrentJoinsNeverExceedCapacityAndStayConsistent() throws Exception {
        GameService service = newService();
        Game game = service.createGame("Host");
        String code = game.getCode();

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                awaitQuietly(ready);
                try {
                    service.joinGame(code, "Player" + n);
                    succeeded.incrementAndGet();
                } catch (RuntimeException ignored) {
                    // "Game is full" / duplicate-name losers of the race.
                }
            }));
        }
        ready.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        Game finalGame = service.getGame(code);
        // Host + 3 joiners exactly; the lock must prevent over-filling past the cap.
        assertEquals(4, finalGame.getPlayers().size());
        assertEquals(3, succeeded.get());
        long distinctIds = finalGame.getPlayers().stream().map(Player::getId).distinct().count();
        assertEquals(4, distinctIds);
        long distinctNames = finalGame.getPlayers().stream().map(Player::getName).distinct().count();
        assertEquals(4, distinctNames);
    }

    @Test
    void concurrentActionsKeepVersionMonotonic() throws Exception {
        GameService service = newService();
        Game game = service.createGame("Host");
        String code = game.getCode();
        // Fill and auto-start a 4-player game.
        service.joinGame(code, "Bob");
        service.joinGame(code, "Cara");
        service.joinGame(code, "Dave");

        long startVersion = service.getGame(code).getVersion();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        // Hammer the same game with (mostly illegal, frequently rejected) actions from all seats.
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                awaitQuietly(ready);
                try {
                    Game g = service.getGame(code);
                    Player p = g.getPlayers().get((int) (Math.random() * g.getPlayers().size()));
                    if (!p.getHand().isEmpty()) {
                        service.attack(code, p.getId(), p.getHand().getFirst());
                    }
                } catch (RuntimeException ignored) {
                    // Illegal/contended moves are rejected without corrupting state.
                }
            }));
        }
        ready.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        // No corruption: state still loads and version only moved forward.
        Game finalGame = service.getGame(code);
        org.junit.jupiter.api.Assertions.assertTrue(finalGame.getVersion() >= startVersion);
        org.junit.jupiter.api.Assertions.assertEquals(4, finalGame.getPlayers().size());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
