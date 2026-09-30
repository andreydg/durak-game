package com.example.durakgame.service.autoplay;

import com.example.durakgame.service.autoplay.LlmCircuitBreaker.Outcome;
import com.example.durakgame.service.autoplay.LlmCircuitBreaker.Permit;
import com.example.durakgame.service.autoplay.LlmCircuitBreaker.State;
import org.junit.jupiter.api.Test;

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

    @Test
    void opensAfterConsecutiveFailures() {
        fail(2);
        assertEquals(State.CLOSED, breaker.state());
        assertEquals(Permit.CALL, breaker.tryAcquire());

        fail(1);

        assertEquals(State.OPEN, breaker.state());
        assertEquals(Permit.REJECTED, breaker.tryAcquire());
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
        assertEquals(Permit.REJECTED, breaker.tryAcquire());

        clock.addAndGet(SECOND);
        assertEquals(Permit.PROBE, breaker.tryAcquire());
        assertEquals(State.HALF_OPEN, breaker.state());
        assertEquals(Permit.REJECTED, breaker.tryAcquire(), "only one probe at a time");
    }

    @Test
    void aSuccessfulProbeClosesTheBreaker() {
        fail(3);
        clock.addAndGet(60 * SECOND);

        breaker.record(breaker.tryAcquire(), Outcome.SUCCESS);

        assertEquals(State.CLOSED, breaker.state());
        assertEquals(Permit.CALL, breaker.tryAcquire());
    }

    @Test
    void aFailedProbeReopensForAnotherCooldown() {
        fail(3);
        clock.addAndGet(60 * SECOND);

        breaker.record(breaker.tryAcquire(), Outcome.FAILURE);

        assertEquals(State.OPEN, breaker.state());
        clock.addAndGet(59 * SECOND);
        assertEquals(Permit.REJECTED, breaker.tryAcquire());
        clock.addAndGet(SECOND);
        assertEquals(Permit.PROBE, breaker.tryAcquire());
    }

    @Test
    void aNeutralProbeFreesTheProbeSlot() {
        fail(3);
        clock.addAndGet(60 * SECOND);

        breaker.record(breaker.tryAcquire(), Outcome.NEUTRAL);

        assertEquals(State.HALF_OPEN, breaker.state());
        assertEquals(Permit.PROBE, breaker.tryAcquire());
    }

    @Test
    void aProbeThatNeverReportsIsReplacedAfterAnotherCooldown() {
        fail(3);
        clock.addAndGet(60 * SECOND);
        assertEquals(Permit.PROBE, breaker.tryAcquire());

        clock.addAndGet(30 * SECOND);
        assertEquals(Permit.REJECTED, breaker.tryAcquire());
        clock.addAndGet(30 * SECOND);
        assertEquals(Permit.PROBE, breaker.tryAcquire());
    }

    @Test
    void lateFailuresFromCallsStartedBeforeOpeningDoNotExtendTheCooldown() {
        Permit early = breaker.tryAcquire();
        fail(3);
        clock.addAndGet(30 * SECOND);

        breaker.record(early, Outcome.FAILURE);

        clock.addAndGet(30 * SECOND);
        assertEquals(Permit.PROBE, breaker.tryAcquire());
    }

    @Test
    void thresholdBelowOneDisablesTheBreaker() {
        LlmCircuitBreaker disabled = new LlmCircuitBreaker(0, Duration.ofSeconds(60), clock::get);
        for (int i = 0; i < 10; i++) {
            disabled.record(disabled.tryAcquire(), Outcome.FAILURE);
        }

        assertEquals(Permit.CALL, disabled.tryAcquire());
    }

    @Test
    void concurrentCallersGetASingleProbe() throws Exception {
        fail(3);
        clock.addAndGet(60 * SECOND);
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Permit>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                start.await();
                return breaker.tryAcquire();
            });
        }
        List<Permit> permits = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Permit>> futures = new ArrayList<>();
            for (Callable<Permit> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            for (Future<Permit> future : futures) {
                permits.add(future.get());
            }
        }

        assertEquals(1, permits.stream().filter(permit -> permit == Permit.PROBE).count());
        assertEquals(threads - 1, permits.stream().filter(permit -> permit == Permit.REJECTED).count());
    }
}
