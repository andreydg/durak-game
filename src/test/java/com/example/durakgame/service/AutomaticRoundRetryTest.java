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
import com.example.durakgame.websocket.GameWebSocketHandler;
import org.junit.jupiter.api.Test;
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
        doThrow(new IllegalStateException("temporary store outage")).doCallRealMethod().when(store).save(any());

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

    @Test
    void retryPublishesACommitWhoseAcknowledgementWasLost() {
        service.resumeAutoPlayIfStalled(seed());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("connection failed after commit");
        }).when(store).save(any());

        timers.getFirst().task().run();
        assertEquals(1, saved().getBoutsCompleted());
        verifyNoInteractions(sockets);
        Game.Snapshot committed = saved().toSnapshot();
        clearInvocations(store);

        timers.get(1).task().run();
        assertEquals(committed, saved().toSnapshot());
        verify(store, never()).save(any());
        verify(sockets).broadcastGameUpdated("RETRY2", 41L);
    }

    @Test
    void backoffIsBoundedAndReadsDoNotRestartIt() {
        service.resumeAutoPlayIfStalled(seed());
        doThrow(new IllegalStateException("store still unavailable")).when(store).save(any());

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

        doCallRealMethod().when(store).save(any());
        service.resumeAutoPlayIfStalled(saved());
        assertEquals(5, timers.size(), "exhaustion releases the pending slot for recovery");
        assertEquals(2500, timers.get(4).delayMs());
        timers.get(4).task().run();
        assertEquals(1, saved().getBoutsCompleted());
    }

    @Test
    void heartbeatDuringBackoffDoesNotInvalidateRetry() {
        service.resumeAutoPlayIfStalled(seed());
        doThrow(new IllegalStateException("temporary store outage")).doCallRealMethod().when(store).save(any());
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
        doThrow(new IllegalStateException("temporary store outage")).doCallRealMethod().when(store).save(any());
        timers.getFirst().task().run();
        service.leaveGame("RETRY2", "human"); // No humans remain, so the room is deleted.
        assertTrue(store.findByCode("RETRY2").isEmpty());
        clearInvocations(store, sockets);
        timers.get(1).task().run();
        verify(store, never()).save(any());
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
            assertTrue(logs.list.stream().noneMatch(event -> event.getFormattedMessage().startsWith("autoplay_resumed ")));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private Game saved() { return store.findByCode("RETRY2").orElseThrow(); }

    private Game seed() {
        long now = Instant.now().toEpochMilli();
        Game game = Game.fromSnapshot(new Game.Snapshot("RETRY2", now, now, "human", GameStatus.IN_PROGRESS,
                Suit.SPADES, null, 0, 1, null, false, 0, 40L,
                List.of(new Game.PlayerSnapshot("bot", "Bot", now, true, null, List.of(Card.fromCode("KD")), "bot-secret"),
                        new Game.PlayerSnapshot("human", "Human", now, false, null, List.of(), "human-secret")),
                List.of(), List.of(new Game.AttackSnapshot(Card.fromCode("6H"), Card.fromCode("7H"), "bot")),
                Set.of(), List.of(), List.of()));
        game.markNotPersisted();
        store.save(game);
        return game;
    }
}
