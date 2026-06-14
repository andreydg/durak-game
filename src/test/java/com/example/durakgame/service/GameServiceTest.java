package com.example.durakgame.service;

import com.example.durakgame.controller.dto.LobbyGameSummary;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Player;
import com.example.durakgame.model.Suit;
import com.example.durakgame.model.ViewerLegalMoves;
import com.example.durakgame.service.autoplay.AutoPlayAction;
import com.example.durakgame.service.autoplay.AutoPlayDecisionEngine;
import com.example.durakgame.service.store.GameStore;
import com.example.durakgame.service.store.InMemoryGameStore;
import com.example.durakgame.service.store.StaleGameWriteException;
import com.example.durakgame.websocket.GameWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServiceTest {

    private final AutoPlayDecisionEngine noOpEngine =
            (game, playerId, legalMoves) -> null;
    private final GameWebSocketHandler webSocket = new GameWebSocketHandler(new ObjectMapper());

    private GameService newService(GameStore store) {
        return new GameService(store, noOpEngine, webSocket);
    }

    private GameService newService(GameStore store, AutoPlayDecisionEngine engine) {
        return new GameService(store, engine, webSocket);
    }

    // --- createGame -------------------------------------------------------

    @Test
    void createGameGeneratesSixCharCodeAndPersistsHost() {
        GameStore store = new InMemoryGameStore();
        GameService service = newService(store);

        Game game = service.createGame("Alice");

        assertEquals(6, game.getCode().length());
        assertEquals(GameStatus.LOBBY, game.getStatus());
        assertEquals(1, game.getPlayers().size());
        assertEquals("Alice", game.getPlayers().getFirst().getName());
        assertTrue(store.findByCode(game.getCode()).isPresent());
    }

    @Test
    void createGameBlankNameGetsRandomName() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("   ");
        String name = game.getPlayers().getFirst().getName();
        assertNotNull(name);
        assertTrue(name.length() >= 2);
    }

    @Test
    void createGameGeneratesDistinctCodes() {
        GameService service = newService(new InMemoryGameStore());
        Set<String> codes = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 200; i++) {
            assertTrue(codes.add(service.createGame("Host").getCode()), "codes must be unique");
        }
    }

    @Test
    void createGameRejectsTooShortAndTooLongNames() {
        GameService service = newService(new InMemoryGameStore());
        assertThrows(IllegalArgumentException.class, () -> service.createGame("x"));
        assertThrows(IllegalArgumentException.class, () -> service.createGame("x".repeat(25)));
    }

    // --- getGame ----------------------------------------------------------

    @Test
    void getGameNormalizesCodeCaseAndWhitespace() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");

        Game found = service.getGame("  " + game.getCode().toLowerCase() + "  ");
        assertEquals(game.getCode(), found.getCode());
    }

    @Test
    void getGameUnknownThrowsNoSuchElement() {
        GameService service = newService(new InMemoryGameStore());
        assertThrows(NoSuchElementException.class, () -> service.getGame("NOPE12"));
    }

    // --- joinGame ---------------------------------------------------------

    @Test
    void joinGameAddsPlayer() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");

        Player joined = service.joinGame(game.getCode(), "Guest");

        assertEquals("Guest", joined.getName());
        assertEquals(2, service.getGame(game.getCode()).getPlayers().size());
    }

    @Test
    void joinGameRejectsDuplicateName() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        assertThrows(IllegalStateException.class, () -> service.joinGame(game.getCode(), "Host"));
    }

    @Test
    void joinGameAutoStartsWhenTableFills() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        service.joinGame(game.getCode(), "Bob");
        service.joinGame(game.getCode(), "Cara");
        service.joinGame(game.getCode(), "Dave");

        Game full = service.getGame(game.getCode());
        assertEquals(GameStatus.IN_PROGRESS, full.getStatus());
        assertEquals(service.getMaxPlayers(), full.getPlayers().size());
    }

    @Test
    void joinGameBlankNameGetsDistinctRandomName() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Boris");
        Player joined = service.joinGame(game.getCode(), "");
        assertFalse(joined.getName().equalsIgnoreCase("Boris"));
    }

    // --- addBot -----------------------------------------------------------

    @Test
    void addBotRequiresHost() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        Player guest = service.joinGame(game.getCode(), "Guest");

        assertThrows(IllegalStateException.class,
                () -> service.addBot(game.getCode(), guest.getId(), null));
    }

    @Test
    void addBotAllowsOnlyOneBot() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        String hostId = game.getHostPlayerId();
        service.addBot(game.getCode(), hostId, null);

        assertThrows(IllegalStateException.class,
                () -> service.addBot(game.getCode(), hostId, null));
    }

    @Test
    void addBotMarksPlayerAsBotAndAppendsElektronikSuffix() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");

        Player bot = service.addBot(game.getCode(), game.getHostPlayerId(), "Hal");

        assertTrue(bot.isBot());
        assertTrue(bot.getName().endsWith("Elektronik"), "got: " + bot.getName());
    }

    @Test
    void addBotBlankNameStillProducesBot() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        Player bot = service.addBot(game.getCode(), game.getHostPlayerId(), "");
        assertTrue(bot.isBot());
        assertTrue(bot.getName().length() >= 2);
    }

    // --- leaveGame --------------------------------------------------------

    @Test
    void leaveLobbyNonHostKeepsRoom() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        Player guest = service.joinGame(game.getCode(), "Guest");

        boolean removed = service.leaveGame(game.getCode(), guest.getId());

        assertFalse(removed);
        assertEquals(1, service.getGame(game.getCode()).getPlayers().size());
    }

    @Test
    void leaveLobbyHostDeletesRoom() {
        GameStore store = new InMemoryGameStore();
        GameService service = newService(store);
        Game game = service.createGame("Host");
        service.joinGame(game.getCode(), "Guest");

        boolean removed = service.leaveGame(game.getCode(), game.getHostPlayerId());

        assertTrue(removed);
        assertTrue(store.findByCode(game.getCode()).isEmpty());
    }

    @Test
    void leaveLobbyLastPlayerDeletesRoom() {
        GameStore store = new InMemoryGameStore();
        GameService service = newService(store);
        Game game = service.createGame("Host");

        boolean removed = service.leaveGame(game.getCode(), game.getHostPlayerId());

        assertTrue(removed);
        assertTrue(store.findByCode(game.getCode()).isEmpty());
    }

    @Test
    void leaveInProgressHostDeletesRoom() {
        GameStore store = new InMemoryGameStore();
        GameService service = newService(store);
        Game game = service.createGame("Host");
        service.joinGame(game.getCode(), "Guest");
        service.startGame(game.getCode(), game.getHostPlayerId());

        boolean removed = service.leaveGame(game.getCode(), game.getHostPlayerId());

        assertTrue(removed);
        assertTrue(store.findByCode(game.getCode()).isEmpty());
    }

    @Test
    void leaveInProgressNonHostResetsToLobby() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        Player guest = service.joinGame(game.getCode(), "Guest");
        service.startGame(game.getCode(), game.getHostPlayerId());

        boolean removed = service.leaveGame(game.getCode(), guest.getId());

        assertFalse(removed);
        Game after = service.getGame(game.getCode());
        assertEquals(GameStatus.LOBBY, after.getStatus());
        assertEquals(1, after.getPlayers().size());
    }

    @Test
    void leaveUnknownPlayerThrows() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        assertThrows(NoSuchElementException.class,
                () -> service.leaveGame(game.getCode(), "ghost"));
    }

    // --- listOpenLobbies --------------------------------------------------

    @Test
    void listOpenLobbiesExcludesInProgressAndFullTables() {
        GameService service = newService(new InMemoryGameStore());
        Game open = service.createGame("Opener");

        Game started = service.createGame("Starter");
        service.joinGame(started.getCode(), "Guest");
        service.startGame(started.getCode(), started.getHostPlayerId());

        List<LobbyGameSummary> lobbies = service.listOpenLobbies();
        List<String> codes = lobbies.stream().map(LobbyGameSummary::code).toList();

        assertTrue(codes.contains(open.getCode()));
        assertFalse(codes.contains(started.getCode()));
    }

    @Test
    void listOpenLobbiesCachesWithinTtl() {
        AtomicInteger reads = new AtomicInteger();
        GameStore counting = new InMemoryGameStore() {
            @Override
            public java.util.Collection<Game> listOpenLobbies() {
                reads.incrementAndGet();
                return super.listOpenLobbies();
            }
        };
        GameService service = newService(counting);
        service.createGame("Host");

        service.listOpenLobbies();
        service.listOpenLobbies();

        assertEquals(1, reads.get(), "second call within TTL should hit the cache");
    }

    // --- mutateGame delegation & retry -----------------------------------

    @Test
    void attackDelegatesToGameAndPersists() {
        SnapshotGameStore store = new SnapshotGameStore();
        GameService service = newService(store);
        store.put(twoPlayerBoutOnEmptyTable());

        Game after = service.attack("TEST01", "h", Card.fromCode("9H"));

        assertEquals(1, after.getTable().size());
        assertEquals("9H", after.getTable().getFirst().getAttackCard().code());
        // Persisted, not just mutated in memory.
        assertEquals(1, service.getGame("TEST01").getTable().size());
    }

    @Test
    void mutateRetriesOnceThenSucceedsOnStaleWrite() {
        SnapshotGameStore store = new SnapshotGameStore();
        store.failNextSaves(1);
        GameService service = newService(store);
        store.put(twoPlayerBoutOnEmptyTable());

        Game after = service.attack("TEST01", "h", Card.fromCode("9H"));

        // Applied exactly once despite the first save being rejected.
        assertEquals(1, after.getTable().size());
        assertEquals(2, store.saveAttempts());
    }

    @Test
    void mutatePropagatesStaleWriteAfterRetriesExhausted() {
        SnapshotGameStore store = new SnapshotGameStore();
        store.failNextSaves(Integer.MAX_VALUE);
        GameService service = newService(store);
        store.put(twoPlayerBoutOnEmptyTable());

        assertThrows(StaleGameWriteException.class,
                () -> service.attack("TEST01", "h", Card.fromCode("9H")));
    }

    // --- autoplay loop integration ---------------------------------------

    @Test
    void scheduledAutoPlayMakesBotTakeWhenItCannotDefend() throws InterruptedException {
        SnapshotGameStore store = new SnapshotGameStore();
        GameService service = newService(store);
        // Human attacker (seat 0); bot defender (seat 1) holds only cards that cannot beat 9H.
        store.put(twoPlayerBotDefenderOnEmptyTable());

        // Human's opening attack schedules the bot's turn.
        service.attack("TEST01", "h", Card.fromCode("9H"));

        boolean took = false;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (service.getGame("TEST01").isTakingCardsInProgress()) {
                took = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(took, "bot defender should auto-take when it cannot beat the attack");
    }

    @Test
    void scheduledAutoPlayUsesEngineDecisionForDefense() throws InterruptedException {
        SnapshotGameStore store = new SnapshotGameStore();
        // Engine instructs the bot defender to beat 9H with 10H (a non-forced, engine-driven move).
        AutoPlayDecisionEngine engine = (game, playerId, legalMoves) -> AutoPlayAction.defend("9H", "10H");
        GameService service = newService(store, engine);
        store.put(twoPlayerBotDefenderCanBeat());

        // The human's opening attack schedules the bot's defending turn.
        service.attack("TEST01", "h", Card.fromCode("9H"));

        boolean defended = false;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<com.example.durakgame.model.AttackEntry> table = service.getGame("TEST01").getTable();
            if (!table.isEmpty() && table.getFirst().isDefended()) {
                defended = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(defended, "bot defender should apply the engine-chosen defense");
    }

    // --- helpers ----------------------------------------------------------

    /** Two humans, in progress, empty table, h=attacker seat0, b=defender seat1. */
    private static Game twoPlayerBoutOnEmptyTable() {
        return inProgress(
                List.of(
                        playerSnapshot("h", false, "9H", "8C"),
                        playerSnapshot("b", false, "7H", "9D")
                ),
                Suit.SPADES, cards("8D", "10D"), 0, 1);
    }

    /** h=human attacker seat0; b=bot defender seat1 with no card able to beat 9H. */
    private static Game twoPlayerBotDefenderOnEmptyTable() {
        return inProgress(
                List.of(
                        playerSnapshot("h", false, "9H", "8C"),
                        playerSnapshot("b", true, "6C", "7D")
                ),
                Suit.SPADES, cards("8D", "10D"), 0, 1);
    }

    /** h=human attacker seat0; b=bot defender seat1 holding 10H able to beat 9H. */
    private static Game twoPlayerBotDefenderCanBeat() {
        return inProgress(
                List.of(
                        playerSnapshot("h", false, "9H", "8C"),
                        playerSnapshot("b", true, "10H", "7D")
                ),
                Suit.SPADES, cards("8D", "10D"), 0, 1);
    }

    private static Game inProgress(
            List<Game.PlayerSnapshot> players,
            Suit trumpSuit,
            List<Card> talon,
            int attackerIndex,
            int defenderIndex
    ) {
        return Game.fromSnapshot(new Game.Snapshot(
                "TEST01",
                0L,
                players.getFirst().id(),
                GameStatus.IN_PROGRESS,
                trumpSuit,
                null,
                attackerIndex,
                defenderIndex,
                null,
                false,
                0,
                0L,
                players,
                talon,
                List.of(),
                Set.of(),
                List.of(),
                List.of()
        ));
    }

    private static Game.PlayerSnapshot playerSnapshot(String id, boolean bot, String... cardCodes) {
        return new Game.PlayerSnapshot(id, id, 0L, bot, null, cards(cardCodes));
    }

    private static List<Card> cards(String... codes) {
        return new ArrayList<>(Arrays.stream(codes).map(Card::fromCode).toList());
    }

    /**
     * Store that models a remote backend: reads return fresh copies decoded from a stored
     * snapshot (never the live object), and saves can be made to fail to simulate a
     * cross-instance lost-update race.
     */
    private static final class SnapshotGameStore implements GameStore {
        private final java.util.Map<String, Game.Snapshot> snapshots = new ConcurrentHashMap<>();
        private final AtomicInteger saveAttempts = new AtomicInteger();
        private final AtomicInteger failuresRemaining = new AtomicInteger();

        void put(Game game) {
            snapshots.put(game.getCode(), game.toSnapshot());
        }

        void failNextSaves(int n) {
            failuresRemaining.set(n);
        }

        int saveAttempts() {
            return saveAttempts.get();
        }

        @Override
        public void save(Game game) {
            saveAttempts.incrementAndGet();
            if (failuresRemaining.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
                throw new StaleGameWriteException(game.getCode(), game.getVersion(), game.getVersion() + 1);
            }
            snapshots.put(game.getCode(), game.toSnapshot());
        }

        @Override
        public Optional<Game> findByCode(String code) {
            Game.Snapshot snap = snapshots.get(code);
            return snap == null ? Optional.empty() : Optional.of(Game.fromSnapshot(snap));
        }

        @Override
        public void deleteByCode(String code) {
            snapshots.remove(code);
        }

        @Override
        public boolean existsByCode(String code) {
            return snapshots.containsKey(code);
        }

        @Override
        public java.util.Collection<Game> listAll() {
            List<Game> all = new ArrayList<>();
            snapshots.values().forEach(s -> all.add(Game.fromSnapshot(s)));
            return all;
        }
    }
}
