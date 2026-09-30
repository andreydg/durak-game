package com.example.durakgame.service.autoplay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmCallBudgetTest {
    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong clock = new AtomicLong();

    @Test
    void allowsABurstOfTenSecondsWorthThenRefillsAtTheConfiguredRate() {
        LlmCallBudget budget = new LlmCallBudget(120, clock::get);
        assertEquals(20, budget.burst());

        for (int i = 0; i < 20; i++) {
            assertTrue(budget.tryAcquire(), "call " + i + " is within the burst");
        }
        assertFalse(budget.tryAcquire());

        clock.addAndGet(SECOND * 6 / 10);
        assertTrue(budget.tryAcquire(), "120 per minute refills one call every half second");
        assertFalse(budget.tryAcquire());
    }

    @Test
    void neverExceedsTheRateOverAMinute() {
        LlmCallBudget budget = new LlmCallBudget(120, clock::get);
        int granted = 0;
        for (int tick = 0; tick < 600; tick++) {
            while (budget.tryAcquire()) {
                granted++;
            }
            clock.addAndGet(SECOND / 10);
        }

        // 20 burst + just under 60 s x 2 calls/s of refill (the last check happens at 59.9 s).
        assertTrue(granted >= 138 && granted <= 140, "granted " + granted);
    }

    @Test
    void smallLimitsStillAllowOneCall() {
        LlmCallBudget budget = new LlmCallBudget(3, clock::get);

        assertEquals(1, budget.burst());
        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire());
        clock.addAndGet(21 * SECOND);
        assertTrue(budget.tryAcquire(), "3 per minute refills one call every 20 seconds");
    }

    @Test
    void limitBelowOneMeansUnlimited() {
        LlmCallBudget budget = new LlmCallBudget(0, clock::get);

        for (int i = 0; i < 10_000; i++) {
            assertTrue(budget.tryAcquire());
        }
    }

    @Test
    void concurrentCallersNeverOverdrawTheBucket() throws Exception {
        LlmCallBudget budget = new LlmCallBudget(120, clock::get);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return budget.tryAcquire();
                }));
            }
            start.countDown();
            int granted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    granted++;
                }
            }
            assertEquals(20, granted);
        }
    }
}
