package com.example.durakgame.service.autoplay;

import com.example.durakgame.service.autoplay.LlmCircuitBreaker.Kind;
import com.example.durakgame.service.autoplay.LlmCircuitBreaker.Outcome;
import com.example.durakgame.service.autoplay.LlmCircuitBreaker.Permit;
import com.example.durakgame.service.autoplay.LlmCircuitBreaker.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmCircuitBreakerTest {
    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong clock = new AtomicLong();
    private final LlmCircuitBreaker breaker = new LlmCircuitBreaker(3, Duration.ofSeconds(60), clock::get);

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            breaker.record(breaker.tryAcquire(), Outcome.FAILURE);
        }
    }

    private Kind acquire() {
        return breaker.tryAcquire().kind();
    }

    @Test
    void opensAfterConsecutiveFailures() {
        fail(2);
        assertEquals(State.CLOSED, breaker.state());
        assertEquals(Kind.CALL, acquire());

        fail(1);

        assertEquals(State.OPEN, breaker.state());
        assertEquals(Kind.REJECTED, acquire());
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        fail(2);
        breaker.record(breaker.tryAcquire(), Outcome.SUCCESS);
        fail(2);

        assertEquals(State.CLOSED, breaker.state());
    }

    @Test
    void neutralOutcomesNeitherTripNorReset() {
        fail(2);
        breaker.record(breaker.tryAcquire(), Outcome.NEUTRAL);
        assertEquals(State.CLOSED, breaker.state());

        fail(1);
        assertEquals(State.OPEN, breaker.state());
    }

    @Test
    void letsExactlyOneProbeThroughAfterTheCooldown() {
        fail(3);
        clock.addAndGet(59 * SECOND);
        assertEquals(Kind.REJECTED, acquire());

        clock.addAndGet(SECOND);
        assertEquals(Kind.PROBE, acquire());
        assertEquals(State.HALF_OPEN, breaker.state());
        assertEquals(Kind.REJECTED, acquire(), "only one probe at a time");
    }

    @Test
    void aSuccessfulProbeClosesTheBreaker() {
        fail(3);
        clock.addAndGet(60 * SECOND);

        breaker.record(breaker.tryAcquire(), Outcome.SUCCESS);

        assertEquals(State.CLOSED, breaker.state());
        assertEquals(Kind.CALL, acquire());
    }

    @Test
    void aFailedProbeReopensForAnotherCooldown() {
        fail(3);
        clock.addAndGet(60 * SECOND);

        breaker.record(breaker.tryAcquire(), Outcome.FAILURE);

        assertEquals(State.OPEN, breaker.state());
        clock.addAndGet(59 * SECOND);
        assertEquals(Kind.REJECTED, acquire());
        clock.addAndGet(SECOND);
        assertEquals(Kind.PROBE, acquire());
    }

    @Test
    void aNeutralProbeFreesTheProbeSlot() {
        fail(3);
        clock.addAndGet(60 * SECOND);

        breaker.record(breaker.tryAcquire(), Outcome.NEUTRAL);

        assertEquals(State.HALF_OPEN, breaker.state());
        assertEquals(Kind.PROBE, acquire());
    }

    @Test
    void aProbeThatNeverReportsIsReplacedAfterAnotherCooldown() {
        fail(3);
        clock.addAndGet(60 * SECOND);
        assertEquals(Kind.PROBE, acquire());

        clock.addAndGet(30 * SECOND);
        assertEquals(Kind.REJECTED, acquire());
        clock.addAndGet(30 * SECOND);
        assertEquals(Kind.PROBE, acquire());
    }

    @Test
    void lateFailuresFromCallsStartedBeforeOpeningDoNotExtendTheCooldown() {
        Permit early = breaker.tryAcquire();
        fail(3);
        clock.addAndGet(30 * SECOND);

        breaker.record(early, Outcome.FAILURE);

        clock.addAndGet(30 * SECOND);
        assertEquals(Kind.PROBE, acquire());
    }

    @Test
    void aLateSuccessFromBeforeOpeningDoesNotCutTheCooldownShort() {
        Permit delayed = breaker.tryAcquire();
        fail(3);
        clock.addAndGet(SECOND);

        breaker.record(delayed, Outcome.SUCCESS);

        assertEquals(State.OPEN, breaker.state());
        assertEquals(Kind.REJECTED, acquire());
        clock.addAndGet(59 * SECOND);
        assertEquals(Kind.PROBE, acquire());
    }

    @Test
    void aLateSuccessFromBeforeOpeningDoesNotStandInForTheProbe() {
        Permit delayed = breaker.tryAcquire();
        fail(3);
        clock.addAndGet(60 * SECOND);
        Permit probe = breaker.tryAcquire();

        breaker.record(delayed, Outcome.SUCCESS);

        assertEquals(State.HALF_OPEN, breaker.state());
        assertEquals(Kind.REJECTED, acquire(), "the probe is still out");
        breaker.record(probe, Outcome.SUCCESS);
        assertEquals(State.CLOSED, breaker.state());
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void aReplacedProbeNoLongerDecidesAnything(Outcome outcome) {
        fail(3);
        clock.addAndGet(60 * SECOND);
        Permit replaced = breaker.tryAcquire();
        clock.addAndGet(60 * SECOND);
        Permit current = breaker.tryAcquire();
        assertEquals(Kind.PROBE, current.kind());

        breaker.record(replaced, outcome);

        assertEquals(State.HALF_OPEN, breaker.state());
        assertEquals(Kind.REJECTED, acquire(), "the current probe keeps its slot");
        breaker.record(current, Outcome.FAILURE);
        assertEquals(State.OPEN, breaker.state());
    }

    @Test
    void failuresFromBeforeTheBreakerClosedAgainDoNotCount() {
        Permit delayed = breaker.tryAcquire();
        fail(3);
        clock.addAndGet(60 * SECOND);
        breaker.record(breaker.tryAcquire(), Outcome.SUCCESS);

        breaker.record(delayed, Outcome.FAILURE);
        fail(2);

        assertEquals(State.CLOSED, breaker.state());
    }

    @Test
    void thresholdBelowOneDisablesTheBreaker() {
        LlmCircuitBreaker disabled = new LlmCircuitBreaker(0, Duration.ofSeconds(60), clock::get);
        for (int i = 0; i < 10; i++) {
            disabled.record(disabled.tryAcquire(), Outcome.FAILURE);
        }

        assertEquals(Kind.CALL, disabled.tryAcquire().kind());
    }

    @Test
    void concurrentCallersGetASingleProbe() throws Exception {
        fail(3);
        clock.addAndGet(60 * SECOND);
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Kind>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();
                return acquire();
            });
        }
        List<Kind> kinds = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Kind>> futures = new ArrayList<>();
            for (Callable<Kind> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            for (Future<Kind> future : futures) {
                kinds.add(future.get());
            }
        }

        assertEquals(1, kinds.stream().filter(kind -> kind == Kind.PROBE).count());
        assertEquals(threads - 1, kinds.stream().filter(kind -> kind == Kind.REJECTED).count());
    }
}
