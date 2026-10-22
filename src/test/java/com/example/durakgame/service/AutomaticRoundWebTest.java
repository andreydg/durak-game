package com.example.durakgame.service;

import com.example.durakgame.controller.ApiExceptionHandler;
import com.example.durakgame.controller.GameController;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Suit;
import com.example.durakgame.service.autoplay.AutoPlayAction;
import com.example.durakgame.service.autoplay.HeuristicAutoPlayDecisionEngine;
import com.example.durakgame.service.store.InMemoryGameStore;
import com.example.durakgame.websocket.GameWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A manual scheduler exposes both persisted states without sleeping through the display pause. */
class AutomaticRoundWebTest {
    private final InMemoryGameStore store = new InMemoryGameStore();
    private final GameWebSocketHandler sockets = mock(GameWebSocketHandler.class);
    private final List<Runnable> timers = new CopyOnWriteArrayList<>();
    private final GameService service = service(timers);

    private GameService service(List<Runnable> tasks) {
        return new GameService(store, (g, id, moves) -> null, new HeuristicAutoPlayDecisionEngine(), sockets,
                GameExpiryPolicy.defaults(), LobbyUpdatePublisher.noop(), tasks::add);
    }

    private MockMvc mvc() {
        when(sockets.botThinkingForGame(anyString())).thenReturn(Map.of());
        return MockMvcBuilders.standaloneSetup(new GameController(service, sockets))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void finalDefenceIsSavedAndBroadcastBeforeTheSeparateSettlement() throws Exception {
        seed(false);
        MockMvc mvc = mvc();
        mvc.perform(post("/api/games/AUTO23/defend").header("X-Durak-Token", "b-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playerId":"b","attackCard":"6H","defenseCard":"7H"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table[0].defenseCard").value("7H"))
                .andExpect(jsonPath("$.version").value(41))
                .andExpect(jsonPath("$.players[1].handSize").value(0));

        assertEquals(0, saved().getBoutsCompleted());
        verify(sockets).broadcastGameUpdated("AUTO23", 41L);
        mvc.perform(get("/api/games/AUTO23").param("viewerPlayerId", "a").header("X-Durak-Token", "a-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[0].hand[0]").value("6D"))
                .andExpect(jsonPath("$.table[0].defenseCard").value("7H"))
                .andExpect(jsonPath("$.legalMoves.canEndRound").value(true));
        assertEquals(1, timers.size(), "reads must not restart a pending pause");

        timers.getFirst().run();

        mvc.perform(get("/api/games/AUTO23").param("viewerPlayerId", "a").header("X-Durak-Token", "a-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table").isEmpty())
                .andExpect(jsonPath("$.version").value(42))
                .andExpect(jsonPath("$.players[0].hand[5]").value("QC"))
                .andExpect(jsonPath("$.attackerPlayerId").value("b"))
                .andExpect(jsonPath("$.legalMoves.canEndRound").value(false));
        var broadcasts = inOrder(sockets);
        broadcasts.verify(sockets).broadcastGameUpdated("AUTO23", 41L);
        broadcasts.verify(sockets).broadcastGameUpdated("AUTO23", 42L);
        assertEquals(1, saved().getBoutsCompleted());
        assertEquals(cards("6D", "8C", "9C", "10C", "JC", "QC"), saved().getPlayers().getFirst().getHand());
        assertEquals(cards("KC", "AC", "8D", "9D", "10D", "JD"), saved().getPlayers().get(1).getHand());
        timers.getFirst().run();
        assertEquals(42L, saved().getVersion());
        verify(sockets, times(1)).broadcastGameUpdated("AUTO23", 42L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyTalonFinishesWithCorrectLoserOrDrawAfterThePause(boolean draw) {
        seed(List.of(player("a", draw ? List.of() : cards("6D")), player("b", cards("7H"))), List.of(), 1);
        service.defend("AUTO23", "b", card("6H"), card("7H"));
        assertEquals(GameStatus.IN_PROGRESS, saved().getStatus());
        timers.getFirst().run();
        assertEquals(GameStatus.FINISHED, saved().getStatus());
        assertEquals(draw ? null : "a", saved().getLoserPlayerId());
        assertEquals(1, saved().getBoutsCompleted());
    }

    @Test
    void noTimerIfDefenderStillHoldsCardsEvenWithoutMatchingAttackRanks() {
        seed(List.of(player("a", cards("KD")), player("b", cards("7H", "8D"))), List.of(), 1);
        service.defend("AUTO23", "b", card("6H"), card("7H"));
        service.resumeAutoPlayIfStalled(saved());
        assertTrue(timers.isEmpty());
        assertFalse(saved().computeViewerLegalMoves("a").canAttack());
        assertTrue(saved().computeViewerLegalMoves("a").canEndRound());
        service.endRound("AUTO23", "a");
        assertEquals(1, saved().getBoutsCompleted());
    }

    @Test
    void manualEndDuringPauseWinsAndTimerDoesNothing() {
        seed(true);
        service.defend("AUTO23", "b", card("6H"), card("7H"));
        service.endRound("AUTO23", "a");
        Game.Snapshot finished = saved().toSnapshot();
        timers.getFirst().run();
        timers.getFirst().run();
        assertEquals(finished, saved().toSnapshot());
        assertEquals(1, saved().getBoutsCompleted());
        verify(sockets, never()).broadcastGameUpdated("AUTO23", 43L);
    }

    @Test
    void interveningVersionChangeInvalidatesOldTimerAndHeartbeatRearmsIt() {
        seed(true);
        service.defend("AUTO23", "b", card("6H"), card("7H"));
        service.heartbeat("AUTO23", "a");
        assertEquals(2, timers.size());
        timers.getFirst().run();
        assertEquals(0, saved().getBoutsCompleted());
        assertEquals(42L, saved().getVersion());
        timers.get(1).run();
        assertEquals(1, saved().getBoutsCompleted());
    }

    @Test
    void oldTimerCannotSettleANewBout() {
        seed(false);
        service.defend("AUTO23", "b", card("6H"), card("7H"));
        service.endRound("AUTO23", "a");
        service.attack("AUTO23", "b", card("8D"));
        Game.Snapshot nextBout = saved().toSnapshot();
        timers.getFirst().run();
        assertEquals(nextBout, saved().toSnapshot());
    }

    @Test
    void departureAndDeletionDuringPauseMakeTheTimerHarmless() {
        seed(true);
        service.defend("AUTO23", "b", card("6H"), card("7H"));
        service.leaveGame("AUTO23", "b");
        Game.Snapshot lobby = saved().toSnapshot();
        timers.getFirst().run();
        assertEquals(lobby, saved().toSnapshot());
        service.leaveGame("AUTO23", "a");
        assertDoesNotThrow(() -> timers.getFirst().run());
        assertTrue(store.findByCode("AUTO23").isEmpty());
    }

    @Test
    void restartRearmsPauseFromPersistedPublicStateWithoutASnapshotChange() {
        seed(true);
        service.defend("AUTO23", "b", card("6H"), card("7H"));
        List<Runnable> afterRestart = new CopyOnWriteArrayList<>();
        GameService restarted = service(afterRestart);
        restarted.resumeAutoPlayIfStalled(restarted.getGame("AUTO23"));
        assertEquals(1, afterRestart.size());
        afterRestart.getFirst().run();
        assertEquals(GameStatus.FINISHED, saved().getStatus());
        timers.getFirst().run(); // Even an old instance's callback cannot settle twice.
        assertEquals(42L, saved().getVersion());
    }

    @Test
    void coAttackersNeedNotApproveAnEmptyDefendersAutomaticEnd() {
        seed(List.of(player("a", cards("KD")), player("b", cards("7D")), player("c", cards("7H"))), List.of(), 2);
        service.defend("AUTO23", "c", card("6H"), card("7H"));
        timers.getFirst().run();
        assertEquals(1, saved().getBoutsCompleted());
        assertEquals("b", saved().getAttackerPlayerId());
        assertTrue(saved().getTable().isEmpty());
    }

    @Test
    void staleBotThrowInCannotBecomeItsNextOpeningLead() {
        seed(List.of(player("a", cards("6D", "KD")),
                new Game.PlayerSnapshot("b", "Bot", 0L, true, null, cards("6C"), "b-secret"),
                player("c", cards("7H", "7D"))), List.of(), 2);
        Game game = saved();
        game.defend("c", card("6H"), card("7H"));
        store.save(game);
        Game planned = saved(); // b plans 6C while the table is covered and c still holds one card.
        assertTrue(planned.computeViewerLegalMoves("b").attackableCardCodes().contains("6C"));
        game.attack("a", card("6D"));
        game.defend("c", card("6D"), card("7D"));
        assertTrue(game.endRoundAutomatically());
        store.save(game);
        assertEquals("b", saved().getAttackerPlayerId());
        assertTrue(saved().computeViewerLegalMoves("b").attackableCardCodes().contains("6C"));
        Game.Snapshot before = saved().toSnapshot();
        assertFalse(service.applyAutoPlayAction(planned, planned.getPlayers().get(1), AutoPlayAction.attack("6C")));
        assertEquals(before, saved().toSnapshot());
        verifyNoInteractions(sockets);
        Game replanned = saved();
        assertTrue(service.applyAutoPlayAction(replanned, replanned.getPlayers().get(1), AutoPlayAction.attack("6C")),
                "the newly planned lead can still be applied");
    }

    @Test
    void botEndRoundCannotCutShortTheFinalDefencePause() {
        seed(true);
        service.defend("AUTO23", "b", card("6H"), card("7H"));
        Game planned = saved();
        assertTrue(planned.computeViewerLegalMoves("a").canEndRound());
        assertFalse(service.applyAutoPlayAction(planned, planned.getPlayers().getFirst(), AutoPlayAction.endRound()));
        assertEquals(0, saved().getBoutsCompleted());
        timers.getFirst().run();
        assertEquals(1, saved().getBoutsCompleted());
    }

    @Test
    void settlementBroadcastRunsAfterReleasingTheGameLock() {
        seed(true);
        service.defend("AUTO23", "b", card("6H"), card("7H"));
        AtomicBoolean heartbeatCompleted = new AtomicBoolean();
        doAnswer(invocation -> {
            // A different thread must be able to acquire the same game's lock during fan-out.
            CompletableFuture.runAsync(() -> service.heartbeat("AUTO23", "a")).get(1, TimeUnit.SECONDS);
            heartbeatCompleted.set(true);
            return null;
        }).when(sockets).broadcastGameUpdated("AUTO23", 42L);
        timers.getFirst().run();
        assertTrue(heartbeatCompleted.get());
    }

    private Game saved() { return store.findByCode("AUTO23").orElseThrow(); }

    private void seed(boolean finalBout) {
        seed(List.of(player("a", cards("6D")), player("b", cards("7H"))), finalBout ? List.of()
                : cards("8C", "9C", "10C", "JC", "QC", "KC", "AC", "8D", "9D", "10D", "JD"), 1);
    }

    private void seed(List<Game.PlayerSnapshot> players, List<Card> talon, int defender) {
        long now = Instant.now().toEpochMilli();
        Game game = Game.fromSnapshot(new Game.Snapshot("AUTO23", now, now, "a", GameStatus.IN_PROGRESS,
                Suit.SPADES, null, 0, defender, null, false, 0, 40L, players, talon,
                List.of(new Game.AttackSnapshot(card("6H"), null, "a")), Set.of(), List.of(), List.of()));
        game.markNotPersisted();
        store.save(game);
    }

    private static Game.PlayerSnapshot player(String id, List<Card> hand) {
        return new Game.PlayerSnapshot(id, id, 0L, false, null, hand, id + "-secret");
    }
    private static Card card(String code) { return Card.fromCode(code); }
    private static List<Card> cards(String... codes) { return Arrays.stream(codes).map(Card::fromCode).toList(); }
}
