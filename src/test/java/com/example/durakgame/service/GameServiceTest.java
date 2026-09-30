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
import com.example.durakgame.service.store.LobbyProjection;
import com.example.durakgame.service.store.StaleGameWriteException;
import com.example.durakgame.websocket.GameWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    private GameService newService(GameStore store, GameExpiryPolicy expiryPolicy) {
        return new GameService(store, noOpEngine, webSocket, expiryPolicy);
    }

    private GameService newService(GameStore store, LobbyUpdatePublisher lobbyUpdatePublisher) {
        return new GameService(
                store,
                noOpEngine,
                webSocket,
                GameExpiryPolicy.defaults(),
                lobbyUpdatePublisher
        );
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

    @Test
    void onlyVisiblePublicLobbyMutationsPublishInvalidations() {
        AtomicInteger invalidations = new AtomicInteger();
        GameService service = newService(new InMemoryGameStore(), invalidations::incrementAndGet);

        Game game = service.createGame("Host");
        assertEquals(1, invalidations.get());

        Player guest = service.joinGame(game.getCode(), "Guest");
        assertEquals(2, invalidations.get());

        service.heartbeat(game.getCode(), game.getHostPlayerId());
        assertEquals(2, invalidations.get(), "heartbeat changes expiry only, not the visible lobby roster");

        service.startGame(game.getCode(), game.getHostPlayerId());
        assertEquals(3, invalidations.get());

        service.leaveGame(game.getCode(), guest.getId());
        assertEquals(4, invalidations.get());
    }

    @Test
    void privateRoomMutationsDoNotInvalidatePublicLobby() {
        AtomicInteger invalidations = new AtomicInteger();
        GameService service = newService(new InMemoryGameStore(), invalidations::incrementAndGet);

        Game game = service.createGame("Host", false);
        Player guest = service.joinGame(game.getCode(), "Guest");
        service.heartbeat(game.getCode(), game.getHostPlayerId());
        service.startGame(game.getCode(), game.getHostPlayerId());
        service.leaveGame(game.getCode(), guest.getId());

        assertEquals(0, invalidations.get());
    }

    @Test
    void lobbyPublishFailureCannotFailSuccessfulMutation() {
        GameStore store = new InMemoryGameStore();
        GameService service = newService(store, () -> {
            throw new IllegalStateException("publisher unavailable");
        });

        Game game = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.createGame("Host"));

        assertTrue(store.existsByCode(game.getCode()));
    }

    @Test
    void privateRoomPersistsButDoesNotAppearInPublicLobby() {
        GameService service = newService(new InMemoryGameStore());

        Game privateGame = service.createGame("Private Host", false);

        assertFalse(privateGame.isPublicRoom());
        assertEquals(privateGame.getCode(), service.getGame(privateGame.getCode()).getCode());
        assertFalse(service.listOpenLobbies().stream().anyMatch(lobby -> lobby.code().equals(privateGame.getCode())));
    }

    @Test
    void quickPlayCreatesPrivateStartedGameWithOneBot() {
        GameService service = newService(new InMemoryGameStore());

        Game game = service.createQuickGame("Solo");

        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
        assertFalse(game.isPublicRoom());
        assertEquals(2, game.getPlayers().size());
        assertEquals(1, game.getPlayers().stream().filter(Player::isBot).count());
        assertTrue(game.getPlayers().stream().allMatch(player -> player.handSize() == 6));
        assertFalse(service.listOpenLobbies().stream().anyMatch(lobby -> lobby.code().equals(game.getCode())));
    }

    @Test
    void rematchPersistsFreshDealForSamePlayers() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService service = newService(store);
        Game finished = finishedGame("DONE01");
        store.save(finished);

        Game rematched = service.rematch(finished.getCode(), finished.getHostPlayerId());

        assertEquals(GameStatus.IN_PROGRESS, rematched.getStatus());
        assertEquals(List.of("host", "guest"), rematched.getPlayers().stream().map(Player::getId).toList());
        assertEquals(GameStatus.IN_PROGRESS, service.getGame(finished.getCode()).getStatus());
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

    @Test
    void getGameDeletesExpiredRoomAndReturnsGoneSemantics() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService service = newService(store, new GameExpiryPolicy(30, 24, 60));
        Game expired = lobbyAt("OLD001", Instant.now().minus(31, ChronoUnit.MINUTES));
        store.save(expired);

        assertThrows(RoomExpiredException.class, () -> service.getGame(expired.getCode()));
        assertFalse(store.existsByCode(expired.getCode()));
    }

    @Test
    void heartbeatAdvancesActivityAndVersion() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        long beforeVersion = game.getVersion();
        Instant beforeActivity = game.getLastActivityAt();

        Game after = service.heartbeat(game.getCode(), game.getHostPlayerId());

        assertTrue(after.getVersion() > beforeVersion);
        assertFalse(after.getLastActivityAt().isBefore(beforeActivity));
    }

    // --- authorization ----------------------------------------------------

    @Test
    void isAuthorizedRequiresMatchingSecretToken() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        Player host = game.getPlayers().getFirst();

        assertTrue(service.isAuthorized(game, host.getId(), host.getSecret()));
        assertFalse(service.isAuthorized(game, host.getId(), "wrong-token"));
        // The public id is NOT a valid token once a real secret exists (no impersonation).
        assertFalse(service.isAuthorized(game, host.getId(), host.getId()));
        assertFalse(service.isAuthorized(game, "ghost", host.getSecret()));
        assertFalse(service.isAuthorized(game, host.getId(), null));
    }

    @Test
    void playerIdIsNotAcceptedAsAToken() {
        // Seats from before per-player tokens decode with a blank secret; their public id no longer works.
        Game legacy = inProgress(
                List.of(playerSnapshot("h", false, "9H"), playerSnapshot("b", false, "7D")),
                Suit.SPADES, cards("8D"), 0, 1);
        GameService service = newService(new InMemoryGameStore());

        assertFalse(service.isAuthorized(legacy, "h", "h"));
        assertFalse(service.isAuthorized(legacy, "h", ""));
    }

    @Test
    void requireAuthorizedAcceptsOnlyTheSeatsSecret() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService service = newService(store);
        Game game = service.createGame("Host");
        Player host = game.getPlayers().getFirst();

        service.requireAuthorized(game.getCode(), host.getId(), host.getSecret());
        assertThrows(UnauthorizedActionException.class,
                () -> service.requireAuthorized(game.getCode(), host.getId(), "bad-token"));
        assertThrows(UnauthorizedActionException.class,
                () -> service.requireAuthorized(game.getCode(), host.getId(), host.getId()));
        assertThrows(UnauthorizedActionException.class,
                () -> service.requireAuthorized(game.getCode(), host.getId(), null));
    }

    @Test
    void authorizedViewerRevealsThePrivateViewOnlyWithTheSecret() {
        GameService service = newService(new InMemoryGameStore());
        Game game = service.createGame("Host");
        Player host = game.getPlayers().getFirst();

        assertEquals(host.getId(), service.authorizedViewer(game, host.getId(), host.getSecret()));
        assertNull(service.authorizedViewer(game, host.getId(), "stale-token"));
        assertNull(service.authorizedViewer(game, host.getId(), null));
        assertNull(service.authorizedViewer(game, null, host.getSecret()));
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
    void leaveLobbyHostTransfersRoomToGuest() {
        GameStore store = new InMemoryGameStore();
        GameService service = newService(store);
        Game game = service.createGame("Host");
        Player guest = service.joinGame(game.getCode(), "Guest");

        boolean removed = service.leaveGame(game.getCode(), game.getHostPlayerId());

        assertFalse(removed);
        Game after = service.getGame(game.getCode());
        assertEquals(guest.getId(), after.getHostPlayerId());
        assertEquals(1, after.getPlayers().size());
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
    void leaveInProgressHostTransfersRoomAndResetsToLobby() {
        GameStore store = new InMemoryGameStore();
        GameService service = newService(store);
        Game game = service.createGame("Host");
        Player guest = service.joinGame(game.getCode(), "Guest");
        service.startGame(game.getCode(), game.getHostPlayerId());

        boolean removed = service.leaveGame(game.getCode(), game.getHostPlayerId());

        assertFalse(removed);
        Game after = service.getGame(game.getCode());
        assertEquals(GameStatus.LOBBY, after.getStatus());
        assertEquals(guest.getId(), after.getHostPlayerId());
        assertEquals(1, after.getPlayers().size());
    }

    @Test
    void leaveHostWithOnlyBotDeletesRoom() {
        GameStore store = new InMemoryGameStore();
        GameService service = newService(store);
        Game game = service.createGame("Host");
        service.addBot(game.getCode(), game.getHostPlayerId(), null);

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
    void leaveFinishedNonHostKeepsResultUntilHostRequestsRematch() {
        GameStore store = new InMemoryGameStore();
        GameService service = newService(store);
        Game finished = finishedGame("DONE02");
        store.save(finished);

        boolean removed = service.leaveGame(finished.getCode(), "guest");

        assertFalse(removed);
        Game afterLeave = service.getGame(finished.getCode());
        assertEquals(GameStatus.FINISHED, afterLeave.getStatus());
        assertEquals(List.of("host"), afterLeave.getPlayers().stream().map(Player::getId).toList());
        assertEquals("guest", afterLeave.getLoserPlayerId());
        assertEquals("Guest", afterLeave.getLoserPlayerName());

        Game rematched = service.rematch(finished.getCode(), "host");
        assertEquals(GameStatus.LOBBY, rematched.getStatus());
        assertEquals(List.of("host"), rematched.getPlayers().stream().map(Player::getId).toList());
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

    @Test
    void listOpenLobbiesDeletesExpiredAndPrioritizesPopulatedRoomsWithoutHeartbeatReordering() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService service = newService(store, new GameExpiryPolicy(30, 24, 60));
        Game old = lobbyAt("OLD001", Instant.now().minus(31, ChronoUnit.MINUTES));
        Game recent = lobbyAt("ZZZ001", Instant.now().minus(1, ChronoUnit.MINUTES));
        Game middle = lobbyAt("MID001", Instant.now().minus(10, ChronoUnit.MINUTES));
        middle.addPlayer("Guest", 4);
        Game refreshedOldSingle = lobbyAt("AAA001", Instant.now());
        store.save(old);
        store.save(middle);
        store.save(recent);
        store.save(refreshedOldSingle);

        List<String> codes = service.listOpenLobbies().stream().map(LobbyGameSummary::code).toList();

        assertEquals(List.of("MID001", "AAA001", "ZZZ001"), codes);
        assertFalse(store.existsByCode("OLD001"));
    }

    @Test
    void listOpenLobbiesAppliesTheSameLobbyPhaseCeilingAsGameReads() {
        InMemoryGameStore store = new InMemoryGameStore();
        GameService service = newService(store, new GameExpiryPolicy(30, 24, 60));
        Instant now = Instant.now();
        Game overAgeWithRecentHeartbeat = lobbyAt(
                "MAXAGE", now.minus(3, ChronoUnit.HOURS), now.minus(1, ChronoUnit.MINUTES));
        store.save(overAgeWithRecentHeartbeat);

        assertTrue(service.listOpenLobbies().isEmpty());
        assertFalse(store.existsByCode("MAXAGE"));
    }

    @Test
    void deletingExpiredLobbyPublishesOneInvalidation() {
        InMemoryGameStore store = new InMemoryGameStore();
        AtomicInteger invalidations = new AtomicInteger();
        GameService service = new GameService(
                store,
                noOpEngine,
                webSocket,
                new GameExpiryPolicy(30, 24, 60),
                invalidations::incrementAndGet
        );
        store.save(lobbyAt("OLD001", Instant.now().minus(31, ChronoUnit.MINUTES)));
        store.save(lobbyAt("NEW001", Instant.now().minus(1, ChronoUnit.MINUTES)));

        service.listOpenLobbies();
        service.listOpenLobbies();

        assertEquals(1, invalidations.get());
    }

    @Test
    void concurrentMutationCannotReplaceInvalidatedCacheWithOlderSnapshot() throws Exception {
        CountDownLatch firstReadCaptured = new CountDownLatch(1);
        CountDownLatch releaseFirstRead = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        InMemoryGameStore store = new InMemoryGameStore() {
            @Override
            public List<LobbyProjection> listOpenLobbySummaries() {
                List<LobbyProjection> snapshot = super.listOpenLobbySummaries();
                if (reads.incrementAndGet() == 1) {
                    firstReadCaptured.countDown();
                    try {
                        if (!releaseFirstRead.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to release first lobby read");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                }
                return snapshot;
            }
        };
        GameService service = newService(store);
        Game first = service.createGame("First Host");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<List<LobbyGameSummary>> listing = pool.submit(service::listOpenLobbies);
            assertTrue(firstReadCaptured.await(2, TimeUnit.SECONDS));
            Game second = service.createGame("Second Host");
            releaseFirstRead.countDown();

            List<String> codes = listing.get(2, TimeUnit.SECONDS).stream()
                    .map(LobbyGameSummary::code)
                    .toList();

            assertTrue(codes.contains(first.getCode()));
            assertTrue(codes.contains(second.getCode()));
            assertEquals(2, reads.get(), "revision change should force one fresh projection read");
        } finally {
            releaseFirstRead.countDown();
            pool.shutdownNow();
        }
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

    @Test
    void illegalEngineDecisionFallsBackInsteadOfStallingTheTable() throws InterruptedException {
        SnapshotGameStore store = new SnapshotGameStore();
        // A near-miss card code: the bot holds 6C, but "6c" is not a legal move as-is.
        AutoPlayDecisionEngine engine = (game, playerId, legalMoves) -> AutoPlayAction.attack("6c");
        GameService service = newService(store, engine);
        store.put(twoPlayerBotOpensAttack());

        service.resumeAutoPlayIfStalled(service.getGame("TEST01"));

        assertTrue(waitUntil(() -> !service.getGame("TEST01").getTable().isEmpty(), 10_000),
                "bot must still open the bout when the engine's move is unusable");
    }

    @Test
    void engineFailureFallsBackInsteadOfStallingTheTable() throws InterruptedException {
        SnapshotGameStore store = new SnapshotGameStore();
        AutoPlayDecisionEngine engine = (game, playerId, legalMoves) -> {
            throw new IllegalStateException("model unavailable");
        };
        GameService service = newService(store, engine);
        store.put(twoPlayerBotOpensAttack());

        service.resumeAutoPlayIfStalled(service.getGame("TEST01"));

        assertTrue(waitUntil(() -> !service.getGame("TEST01").getTable().isEmpty(), 10_000),
                "bot must still open the bout when the engine throws");
    }

    @Test
    void humanMoveDuringBotDeliberationIsNotLost() throws Exception {
        SnapshotGameStore store = new SnapshotGameStore();
        CountDownLatch engineEntered = new CountDownLatch(1);
        CountDownLatch releaseEngine = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        // First decision is slow; by the time it lands, the human's throw-in has made it illegal.
        AutoPlayDecisionEngine engine = (game, playerId, legalMoves) -> {
            if (calls.incrementAndGet() == 1) {
                engineEntered.countDown();
                try {
                    releaseEngine.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return AutoPlayAction.transfer("9C");
        };
        GameService service = newService(store, engine);
        store.put(twoPlayerBotDefenderCanTransfer());

        service.attack("TEST01", "h", Card.fromCode("9H"));
        assertTrue(engineEntered.await(5, TimeUnit.SECONDS));
        // Legal throw-in while the bot deliberates; afterwards the bot's transfer no longer fits.
        service.attack("TEST01", "h", Card.fromCode("9D"));
        releaseEngine.countDown();

        assertTrue(waitUntil(() -> {
            Game game = service.getGame("TEST01");
            return game.isTakingCardsInProgress()
                    || game.getTable().stream().anyMatch(com.example.durakgame.model.AttackEntry::isDefended);
        }, 20_000), "bot defender must respond to the throw-in instead of waiting forever");
    }

    @Test
    void readPathsResumeABotTurnNothingIsDriving() throws InterruptedException {
        SnapshotGameStore store = new SnapshotGameStore();
        AtomicInteger calls = new AtomicInteger();
        AutoPlayDecisionEngine engine = (game, playerId, legalMoves) -> {
            calls.incrementAndGet();
            return AutoPlayAction.attack("6C");
        };
        GameService service = newService(store, engine);
        // Simulates a restart: the bot owes the opening attack but no pass is running.
        store.put(twoPlayerBotOpensAttack());

        service.heartbeat("TEST01", "h");

        assertTrue(waitUntil(() -> !service.getGame("TEST01").getTable().isEmpty(), 10_000),
                "a heartbeat should restart the owed bot turn");
        assertEquals(1, calls.get());
    }

    @Test
    void resumeDoesNothingWhenTheBotOwesNoMove() throws InterruptedException {
        SnapshotGameStore store = new SnapshotGameStore();
        AtomicInteger calls = new AtomicInteger();
        GameService service = newService(store, (game, playerId, legalMoves) -> {
            calls.incrementAndGet();
            return null;
        });
        // The human attacker is to move; the bot defender has nothing to answer yet.
        store.put(twoPlayerBotDefenderCanBeat());

        service.resumeAutoPlayIfStalled(service.getGame("TEST01"));
        Thread.sleep(300);

        assertEquals(0, calls.get());
        assertTrue(service.getGame("TEST01").getTable().isEmpty());
    }

    @Test
    void aBotThatAlreadyPassedIsNotDrivenAgain() throws InterruptedException {
        SnapshotGameStore store = new SnapshotGameStore();
        AtomicInteger calls = new AtomicInteger();
        GameService service = newService(store, (game, playerId, legalMoves) -> {
            calls.incrementAndGet();
            return AutoPlayAction.attack("6D");
        });
        // c beat a's six; bot b already approved ending the bout and could only throw in 6D optionally.
        long now = Instant.now().toEpochMilli();
        store.put(Game.fromSnapshot(new Game.Snapshot(
                "TEST01", now, now, "a", GameStatus.IN_PROGRESS, Suit.SPADES, null, 0, 2, null, false, 0, 0L,
                List.of(playerSnapshot("a", false, "9C", "10C"),
                        playerSnapshot("b", true, "6D", "KD"),
                        playerSnapshot("c", false, "8D", "9D")),
                cards("8S", "10S"),
                List.of(new Game.AttackSnapshot(Card.fromCode("6H"), Card.fromCode("7H"), "a")),
                Set.of("b"), List.of(), List.of())));

        service.resumeAutoPlayIfStalled(service.getGame("TEST01"));
        service.heartbeat("TEST01", "a");
        Thread.sleep(300);

        assertEquals(0, calls.get(), "an optional throw-in after passing is not a move the table waits on");
    }

    @Test
    void botTakesAtOnceWhenAnAttackCannotBeBeaten() throws InterruptedException {
        SnapshotGameStore store = new SnapshotGameStore();
        AtomicInteger calls = new AtomicInteger();
        GameService service = newService(store, (game, playerId, legalMoves) -> {
            calls.incrementAndGet();
            return AutoPlayAction.defend("9H", "10H");
        });
        // 10H beats 9H, but nothing beats 9D and no nine is available to transfer.
        store.put(withTable(twoPlayerBotDefender("10H", "7D", "6D"),
                attack("9H", "h"), attack("9D", "h")));

        service.resumeAutoPlayIfStalled(service.getGame("TEST01"));

        assertTrue(waitUntil(() -> service.getGame("TEST01").isTakingCardsInProgress(), 10_000));
        assertEquals(0, calls.get(), "defending one card first would only open more throw-ins");
    }

    @Test
    void aLegalTransferIsLeftToTheEngineInsteadOfForcingATake() throws InterruptedException {
        SnapshotGameStore store = new SnapshotGameStore();
        AtomicInteger calls = new AtomicInteger();
        GameService service = newService(store, (game, playerId, legalMoves) -> {
            calls.incrementAndGet();
            return AutoPlayAction.transfer("9C");
        });
        // 9D cannot be beaten, but transferring with 9C passes both attacks on.
        store.put(withTable(twoPlayerBotDefender("10H", "9C", "7D"),
                attack("9H", "h"), attack("9D", "h")));

        service.resumeAutoPlayIfStalled(service.getGame("TEST01"));

        assertTrue(waitUntil(() -> "h".equals(service.getGame("TEST01").getDefenderPlayerId()), 10_000));
        assertEquals(1, calls.get());
    }

    @Test
    void thinkingTextDuringATakeDoesNotRevealWhetherTheBotCanThrowIn() throws Exception {
        SnapshotGameStore store = new SnapshotGameStore();
        CountDownLatch engineEntered = new CountDownLatch(1);
        CountDownLatch releaseEngine = new CountDownLatch(1);
        GameService service = newService(store, (game, playerId, legalMoves) -> {
            engineEntered.countDown();
            try {
                releaseEngine.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return AutoPlayAction.endRound();
        });
        // Human defender h is taking; bot attacker b holds a matching six it could throw in.
        long now = Instant.now().toEpochMilli();
        Game taking = Game.fromSnapshot(new Game.Snapshot(
                "TEST01", now, now, "h", GameStatus.IN_PROGRESS, Suit.SPADES, null, 1, 0, null, true, 6, 0L,
                List.of(playerSnapshot("h", false, "7H", "8H", "9H", "10H", "JH"),
                        playerSnapshot("b", true, "6D", "KS", "QC")),
                cards("8D", "10D"), List.of(attack("6C", "b")), Set.of(), List.of(), List.of()));
        store.put(taking);

        service.resumeAutoPlayIfStalled(service.getGame("TEST01"));
        assertTrue(engineEntered.await(5, TimeUnit.SECONDS));
        try {
            assertEquals(Map.of("b", "thinking..."), webSocket.botThinkingForGame("TEST01"));
        } finally {
            releaseEngine.countDown();
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    // --- helpers ----------------------------------------------------------

    /** h=human defender seat0; b=bot attacker seat1 who must open the bout. */
    private static Game twoPlayerBotOpensAttack() {
        return inProgress(
                List.of(
                        playerSnapshot("h", false, "9H", "8C"),
                        playerSnapshot("b", true, "6C", "7D")
                ),
                Suit.SPADES, cards("8D", "10D"), 1, 0);
    }

    /** h=human attacker seat0 with three cards; b=bot defender seat1 holding {@code botCards}. */
    private static Game twoPlayerBotDefender(String... botCards) {
        return inProgress(
                List.of(
                        playerSnapshot("h", false, "KH", "QH", "JH"),
                        playerSnapshot("b", true, botCards)
                ),
                Suit.SPADES, cards("8D", "10D"), 0, 1);
    }

    private static Game.AttackSnapshot attack(String card, String attackerId) {
        return new Game.AttackSnapshot(Card.fromCode(card), null, attackerId);
    }

    private static Game withTable(Game game, Game.AttackSnapshot... table) {
        Game.Snapshot s = game.toSnapshot();
        return Game.fromSnapshot(new Game.Snapshot(
                s.code(), s.createdAtEpochMs(), s.lastActivityAtEpochMs(), s.hostPlayerId(), s.status(),
                s.trumpSuit(), s.trumpCard(), s.attackerIndex(), s.defenderIndex(), s.loserPlayerId(),
                s.takingCardsInProgress(), s.takeLimit(), s.version(), s.players(), s.talon(),
                List.of(table), s.endRoundApprovals(), s.discardedCards(), s.knownCardsByPlayer()));
    }

    /** h=human attacker seat0 holding a second nine; b=bot defender seat1 who can transfer with 9C. */
    private static Game twoPlayerBotDefenderCanTransfer() {
        return inProgress(
                List.of(
                        playerSnapshot("h", false, "9H", "9D", "8C"),
                        playerSnapshot("b", true, "9C", "10H", "7D", "6D")
                ),
                Suit.SPADES, cards("QD", "KD"), 0, 1);
    }

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
        long now = Instant.now().toEpochMilli();
        return Game.fromSnapshot(new Game.Snapshot(
                "TEST01",
                now,
                now,
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

    private static Game lobbyAt(String code, Instant lastActivityAt) {
        return lobbyAt(code, lastActivityAt, lastActivityAt);
    }

    private static Game lobbyAt(String code, Instant createdAt, Instant lastActivityAt) {
        long createdTimestamp = createdAt.toEpochMilli();
        long activityTimestamp = lastActivityAt.toEpochMilli();
        Game.PlayerSnapshot host = new Game.PlayerSnapshot(
                "host", "Host", createdTimestamp, false, null, List.of(), "secret");
        return Game.fromSnapshot(new Game.Snapshot(
                code, createdTimestamp, activityTimestamp, "host", GameStatus.LOBBY,
                null, null, -1, -1, null, false, 0, 0L,
                List.of(host), List.of(), List.of(), Set.of(), List.of(), List.of()
        ));
    }

    private static Game finishedGame(String code) {
        long now = Instant.now().toEpochMilli();
        return Game.fromSnapshot(new Game.Snapshot(
                code, now, now, "host", GameStatus.FINISHED,
                Suit.SPADES, Card.fromCode("6S"), -1, -1, "guest", false, 0, 4L,
                List.of(
                        new Game.PlayerSnapshot("host", "Host", now, false, null, List.of(), "host-secret"),
                        new Game.PlayerSnapshot("guest", "Guest", now, false, null, cards("9C"), "guest-secret")
                ),
                List.of(), List.of(), Set.of(), List.of(), List.of()
        ));
    }

    private static Game.PlayerSnapshot playerSnapshot(String id, boolean bot, String... cardCodes) {
        return new Game.PlayerSnapshot(id, id, 0L, bot, null, cards(cardCodes), "");
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
