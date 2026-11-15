package com.example.durakgame.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.durakgame.model.Card;
import com.example.durakgame.model.Game;
import com.example.durakgame.model.GameStatus;
import com.example.durakgame.model.Suit;
import com.example.durakgame.service.autoplay.HeuristicAutoPlayDecisionEngine;
import com.example.durakgame.service.store.InMemoryGameStore;
import com.example.durakgame.service.store.GameStoreException;
import com.example.durakgame.service.store.StaleGameWriteException;
import com.example.durakgame.websocket.GameWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutomaticRoundRetryTest {
    private record Scheduled(Runnable task, long delayMs) {}
    private final InMemoryGameStore store = spy(new InMemoryGameStore());
    private final GameWebSocketHandler sockets = mock(GameWebSocketHandler.class);
    private final List<Scheduled> timers = new ArrayList<>();
    private final GameService service = service((task, delay) -> timers.add(new Scheduled(task, delay)));

    private GameService service(BiConsumer<Runnable, Long> scheduler) {
        return new GameService(store, (g, id, moves) -> null, new HeuristicAutoPlayDecisionEngine(), sockets,
                GameExpiryPolicy.defaults(), LobbyUpdatePublisher.noop(), scheduler);
    }

    @Test
    void failedSettlementRetriesWithoutAnyClientReadEvenWithOnlyABotAttacker() {
        Game covered = seed();
        service.resumeAutoPlayIfStalled(covered);
        doThrow(new IllegalStateException("temporary store outage")).doCallRealMethod().when(store).saveWithReceipt(any(), anyString());

        assertEquals(2500, timers.getFirst().delayMs());
        timers.getFirst().task().run();
        assertEquals(2000, timers.get(1).delayMs());
        assertEquals(covered.toSnapshot(), saved().toSnapshot(), "failed writes publish no partial settlement");
        verifyNoInteractions(sockets);

        timers.get(1).task().run(); // No service read/resume between the failed save and its retry.
        assertEquals(GameStatus.FINISHED, saved().getStatus());
        assertEquals(1, saved().getBoutsCompleted());
        assertEquals(2, timers.size());
        verify(sockets, times(1)).broadcastGameUpdated("RETRY2", 41L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryPublishesAndLogsACommitWhoseAcknowledgementWasLost(boolean stale) {
        Game before = seed();
        ExecutorService worker = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "autoPlayExecutor", worker);
        service.resumeAutoPlayIfStalled(before);
        doAnswer(invocation -> {
            Game attempted = invocation.getArgument(0);
            long loaded = attempted.getLoadedVersion();
            invocation.callRealMethod();
            // Firestore never marks the caller persisted when commit acknowledgement is lost.
            ReflectionTestUtils.setField(attempted, "loadedVersion", loaded);
            throw stale ? new StaleGameWriteException("RETRY2", 40, 41)
                    : new GameStoreException("connection failed after commit");
        }).when(store).saveWithReceipt(any(), anyString());
        Logger logger = (Logger) LoggerFactory.getLogger(GameLifecycleLog.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            timers.getFirst().task().run();
            assertEquals(1, saved().getBoutsCompleted());
            Game.Snapshot committed = saved().toSnapshot();
            if (!stale) {
                verifyNoInteractions(sockets, worker);
                assertEquals(2000, timers.get(1).delayMs());
                timers.get(1).task().run();
            }
            assertEquals(committed, saved().toSnapshot());
            verify(store, times(1)).saveWithReceipt(any(), anyString());
            verify(sockets, times(1)).broadcastGameUpdated("RETRY2", 41L);
            verify(worker, times(1)).execute(any());
            clearInvocations(store);
            for (Scheduled timer : List.copyOf(timers)) timer.task().run();
            verifyNoInteractions(store);
            synchronized (logs) {
                List<String> ended = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(line -> line.startsWith("game_ended code=RETRY2 ")).toList();
                assertEquals(1, ended.size(), ended.toString());
                assertTrue(ended.getFirst().contains("deal=" + before.getDealId() + " endedBy=automatic bouts=1"));
            }
            verify(sockets, times(1)).broadcastGameUpdated("RETRY2", 41L);
            verify(worker, times(1)).execute(any());
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void backoffIsBoundedAndReadsDoNotRestartIt() {
        service.resumeAutoPlayIfStalled(seed());
        doThrow(new IllegalStateException("store still unavailable")).when(store).saveWithReceipt(any(), anyString());

        for (int i = 0; i < 4; i++) {
            timers.get(i).task().run();
            if (i < 3) {
                service.resumeAutoPlayIfStalled(saved());
                assertEquals(i + 2, timers.size(), "a read must not create a competing timer during backoff");
            }
        }
        assertEquals(List.of(2500L, 2000L, 4000L, 6000L), timers.stream().map(Scheduled::delayMs).toList());
        assertEquals(0, saved().getBoutsCompleted());
        verifyNoInteractions(sockets);

        doCallRealMethod().when(store).saveWithReceipt(any(), anyString());
        service.resumeAutoPlayIfStalled(saved());
        assertEquals(5, timers.size(), "exhaustion releases the pending slot for recovery");
        assertEquals(2500, timers.get(4).delayMs());
        clearInvocations(store);
        timers.getFirst().task().run();
        verifyNoInteractions(store); // An obsolete task cannot steal a new task for the SAME bout.
        timers.get(4).task().run();
        assertEquals(1, saved().getBoutsCompleted());
    }

    @Test
    void botFailuresUseTheSameBoundedBackoffAsSettlement() {
        Game ordinaryBout = seed(cards("9D"));
        List<Runnable> workers = new ArrayList<>();
        ExecutorService worker = mock(ExecutorService.class);
        doAnswer(invocation -> { workers.add(invocation.getArgument(0)); return null; })
                .when(worker).execute(any());
        ReflectionTestUtils.setField(service, "autoPlayExecutor", worker);
        doThrow(new GameStoreException("read outage")).when(store).findByCode("RETRY2");
        service.resumeAutoPlayIfStalled(ordinaryBout);
        workers.getFirst().run();
        for (int i = 0; i < 3; i++) {
            service.resumeAutoPlayIfStalled(ordinaryBout);
            assertEquals(1, workers.size(), "retry ownership prevents a parallel bot pass");
            timers.get(i).task().run();
        }
        assertEquals(List.of(2000L, 4000L, 6000L), timers.stream().map(Scheduled::delayMs).toList());
        service.resumeAutoPlayIfStalled(ordinaryBout);
        assertEquals(2, workers.size(), "exhaustion releases the bot slot for read recovery");
        workers.get(1).run();
        assertEquals(2000, timers.get(3).delayMs(), "a new pass starts at the first retry delay");
    }

    @Test
    void recoveredCommitResumesTheRealBotLoopWithoutAClientRead() throws InterruptedException {
        long now = Instant.now().toEpochMilli();
        Game game = Game.fromSnapshot(new Game.Snapshot("RETRY2", now, now, "human", GameStatus.IN_PROGRESS,
                Suit.SPADES, null, 0, 1, null, false, 0, 40L,
                List.of(new Game.PlayerSnapshot("human", "Human", now, false, null, cards("KD"), "human-secret"),
                        new Game.PlayerSnapshot("bot", "Bot", now, true, null, List.of(), "bot-secret")),
                cards("8C", "9C", "10C", "JC", "QC", "KC"),
                List.of(new Game.AttackSnapshot(Card.fromCode("6H"), Card.fromCode("7H"), "human")),
                Set.of(), List.of(), List.of()));
        game.markNotPersisted();
        store.save(game);
        AtomicBoolean firstSave = new AtomicBoolean(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (firstSave.getAndSet(false)) throw new StaleGameWriteException("RETRY2", 40, 41);
            return null;
        }).when(store).saveWithReceipt(any(), anyString());
        CountDownLatch botPlayed = new CountDownLatch(1);
        doAnswer(invocation -> { botPlayed.countDown(); return null; })
                .when(sockets).broadcastGameUpdated("RETRY2", 42L);

        service.resumeAutoPlayIfStalled(game);
        timers.getFirst().task().run();
        assertTrue(botPlayed.await(6, TimeUnit.SECONDS), "the recovered settlement must schedule the next bot lead");
        Game advanced = saved();
        assertEquals(1, advanced.getBoutsCompleted());
        assertEquals(Card.fromCode("KC"), advanced.getTable().getFirst().getAttackCard());
        assertEquals("bot", advanced.getTable().getFirst().getAttackerId());
        verify(sockets).broadcastGameUpdated("RETRY2", 41L);
        verify(sockets).broadcastGameUpdated("RETRY2", 42L);
    }

    @Test
    void recoveredCommitLogsItsOriginalDealEvenAfterARematch() {
        Game covered = seed();
        ExecutorService worker = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "autoPlayExecutor", worker);
        service.resumeAutoPlayIfStalled(covered);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new GameStoreException("lost acknowledgement");
        }).doCallRealMethod().when(store).saveWithReceipt(any(), anyString());
        timers.getFirst().task().run();
        Game rematch = saved();
        rematch.rematch("human");
        store.save(rematch);
        Game.Snapshot beforeRetry = saved().toSnapshot();
        Logger logger = (Logger) LoggerFactory.getLogger(GameLifecycleLog.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            clearInvocations(store);
            timers.get(1).task().run();
            assertEquals(beforeRetry, saved().toSnapshot());
            verify(store, never()).saveWithReceipt(any(), anyString());
            verify(sockets).broadcastGameUpdated("RETRY2", beforeRetry.version());
            verify(worker).execute(any());
            synchronized (logs) {
                List<String> ended = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(line -> line.startsWith("game_ended code=RETRY2 ")).toList();
                assertEquals(1, ended.size());
                assertTrue(ended.getFirst().contains("deal=" + covered.getDealId() + " endedBy=automatic bouts=1"));
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void competingWritersIdenticalSettlementIsNotClaimedAsOurCommit(boolean automaticWinner, boolean stale) {
        Game covered = seed(List.of(), false);
        List<Scheduled> otherTimers = new ArrayList<>();
        GameWebSocketHandler otherSockets = mock(GameWebSocketHandler.class);
        GameService other = new GameService(store, (g, id, moves) -> null,
                new HeuristicAutoPlayDecisionEngine(), otherSockets, GameExpiryPolicy.defaults(),
                LobbyUpdatePublisher.noop(), (task, delay) -> otherTimers.add(new Scheduled(task, delay)));
        ExecutorService worker = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "autoPlayExecutor", worker);
        ReflectionTestUtils.setField(other, "autoPlayExecutor", mock(ExecutorService.class));
        service.resumeAutoPlayIfStalled(covered);
        AtomicReference<Game> losingCandidate = new AtomicReference<>();
        doAnswer(invocation -> {
            Game candidate = invocation.getArgument(0);
            if (losingCandidate.compareAndSet(null, candidate)) {
                if (automaticWinner) {
                    other.resumeAutoPlayIfStalled(covered);
                    otherTimers.getFirst().task().run();
                } else {
                    other.endRound("RETRY2", "bot", covered.getBoutId()); // The seat is human in this fixture.
                }
                throw stale ? new StaleGameWriteException("RETRY2", 40, 41)
                        : new GameStoreException("save failed without committing");
            }
            // Even identical state/timestamps cannot establish which operation won the write.
            ReflectionTestUtils.setField(candidate, "lastActivityAt", losingCandidate.get().getLastActivityAt());
            assertEquals(losingCandidate.get().toSnapshot(), candidate.toSnapshot());
            return invocation.callRealMethod();
        }).when(store).saveWithReceipt(any(), anyString());
        Logger logger = (Logger) LoggerFactory.getLogger(GameLifecycleLog.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            timers.getFirst().task().run();
            if (!stale) timers.get(1).task().run();
            assertEquals(GameStatus.FINISHED, saved().getStatus());
            verifyNoInteractions(sockets, worker);
            synchronized (logs) {
                List<String> ended = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(line -> line.startsWith("game_ended code=RETRY2 ")).toList();
                assertEquals(1, ended.size(), ended.toString());
                assertTrue(ended.getFirst().contains("endedBy=" + (automaticWinner ? "automatic" : "player")));
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void ownReceiptSurvivesHeartbeatAndAReadFailureDuringRecovery() {
        Game covered = seed();
        ExecutorService worker = mock(ExecutorService.class);
        ReflectionTestUtils.setField(service, "autoPlayExecutor", worker);
        service.resumeAutoPlayIfStalled(covered);
        doAnswer(invocation -> {
            Game attempted = invocation.getArgument(0);
            long loaded = attempted.getLoadedVersion();
            invocation.callRealMethod();
            ReflectionTestUtils.setField(attempted, "loadedVersion", loaded);
            throw new GameStoreException("lost acknowledgement");
        }).when(store).saveWithReceipt(any(), anyString());
        timers.getFirst().task().run();
        service.heartbeat("RETRY2", "human");
        Game.Snapshot afterHeartbeat = saved().toSnapshot();
        doThrow(new GameStoreException("read-back unavailable")).doCallRealMethod()
                .when(store).hasWriteReceipt(anyString(), anyString());
        timers.get(1).task().run();
        verifyNoInteractions(sockets, worker);
        assertEquals(4000, timers.get(2).delayMs());
        timers.get(2).task().run();
        assertEquals(afterHeartbeat, saved().toSnapshot());
        verify(store, times(1)).saveWithReceipt(any(), anyString());
        verify(sockets).broadcastGameUpdated("RETRY2", afterHeartbeat.version());
        verify(worker).execute(any());
    }

    @Test
    void heartbeatDuringBackoffDoesNotInvalidateRetry() {
        service.resumeAutoPlayIfStalled(seed());
        doThrow(new IllegalStateException("temporary store outage")).doCallRealMethod().when(store).saveWithReceipt(any(), anyString());
        timers.getFirst().task().run();
        String bout = saved().getBoutId();
        service.heartbeat("RETRY2", "human");
        service.resumeAutoPlayIfStalled(saved());
        assertEquals(bout, saved().getBoutId());
        assertEquals(2, timers.size());
        timers.get(1).task().run();
        assertEquals(1, saved().getBoutsCompleted());
        assertEquals(42L, saved().getVersion());
    }

    @Test
    void lateRetryCannotSettleAfterDeparture() {
        service.resumeAutoPlayIfStalled(seed());
        doThrow(new IllegalStateException("temporary store outage")).doCallRealMethod().when(store).saveWithReceipt(any(), anyString());
        timers.getFirst().task().run();
        service.leaveGame("RETRY2", "human"); // No humans remain, so the room is deleted.
        assertTrue(store.findByCode("RETRY2").isEmpty());
        clearInvocations(store, sockets);
        timers.get(1).task().run();
        verify(store, never()).saveWithReceipt(any(), anyString());
        verifyNoInteractions(sockets);
    }

    @Test
    void rejectedSubmissionReleasesPendingSlot() {
        AtomicInteger submissions = new AtomicInteger();
        GameService rejecting = service((task, delay) -> {
            if (submissions.getAndIncrement() == 0) throw new RejectedExecutionException("shutting down");
            timers.add(new Scheduled(task, delay));
        });
        Game covered = seed();
        assertDoesNotThrow(() -> rejecting.resumeAutoPlayIfStalled(covered));
        assertTrue(timers.isEmpty());
        rejecting.resumeAutoPlayIfStalled(covered);
        assertEquals(1, timers.size());
        timers.getFirst().task().run();
        assertEquals(1, saved().getBoutsCompleted());
    }

    @Test
    void rejectionWhenDelayActuallyExpiresIsHandled() throws InterruptedException {
        GameService rejecting = service(null); // Exercise the production delayed-executor adapter.
        ExecutorService worker = (ExecutorService) ReflectionTestUtils.getField(rejecting, "autoPlayExecutor");
        assertNotNull(worker);
        worker.shutdown();
        CountDownLatch rejected = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();
        ReflectionTestUtils.invokeMethod(rejecting, "scheduleDelayed",
                (Runnable) () -> ran.set(true), 1L, (Runnable) rejected::countDown);
        assertTrue(rejected.await(2, TimeUnit.SECONDS), "the rejection happens on the delay thread");
        assertFalse(ran.get());
    }

    @Test
    void readsDuringPauseDoNotLogOrLaunchBotRecovery() {
        Logger logger = (Logger) LoggerFactory.getLogger(GameService.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            Game covered = seed();
            for (int i = 0; i < 5; i++) service.resumeAutoPlayIfStalled(covered);
            assertEquals(1, timers.size());
            synchronized (logs) {
                assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .noneMatch(line -> line.startsWith("autoplay_resumed code=RETRY2 ")));
            }
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private Game saved() { return store.findByCode("RETRY2").orElseThrow(); }

    private static List<Card> cards(String... codes) {
        return java.util.Arrays.stream(codes).map(Card::fromCode).toList();
    }

    private Game seed() { return seed(List.of()); }

    private Game seed(List<Card> defenderHand) { return seed(defenderHand, true); }

    private Game seed(List<Card> defenderHand, boolean botAttacker) {
        // Manual timers run immediately: keep the original deal distinct from an
        // immediate rematch instead of relying on the wall clock ticking a millisecond.
        long now = Instant.now().minusSeconds(60).toEpochMilli();
        Game game = Game.fromSnapshot(new Game.Snapshot("RETRY2", now, now, "human", GameStatus.IN_PROGRESS,
                Suit.SPADES, null, 0, 1, null, false, 0, 40L,
                List.of(new Game.PlayerSnapshot("bot", "Bot", now, botAttacker, null, List.of(Card.fromCode("KD")), "bot-secret"),
                        new Game.PlayerSnapshot("human", "Human", now, false, null, defenderHand, "human-secret")),
                List.of(), List.of(new Game.AttackSnapshot(Card.fromCode("6H"), Card.fromCode("7H"), "bot")),
                Set.of(), List.of(), List.of()));
        game.markNotPersisted();
        store.save(game);
        return game;
    }
}
