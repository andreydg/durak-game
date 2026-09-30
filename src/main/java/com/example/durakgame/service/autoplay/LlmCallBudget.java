package com.example.durakgame.service.autoplay;

import java.util.function.LongSupplier;

/**
 * Global token bucket for model calls across every game on this instance. It caps spend even if
 * request-level rate limiting is bypassed: when it is empty, bots use the heuristic instead.
 *
 * <p>Refills at {@code maxCallsPerMinute}; the burst is ten seconds' worth of calls (20 at the
 * default 120 per minute). A limit below 1 disables the budget. Thread-safe.
 */
final class LlmCallBudget {
    private final boolean unlimited;
    private final double capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefillNanos;

    LlmCallBudget(int maxCallsPerMinute, LongSupplier nanoClock) {
        this.unlimited = maxCallsPerMinute < 1;
        this.capacity = unlimited ? 0 : Math.max(1, Math.round(maxCallsPerMinute / 6.0));
        this.tokensPerNano = unlimited ? 0 : maxCallsPerMinute / 60_000_000_000.0;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefillNanos = nanoClock.getAsLong();
    }

    synchronized boolean tryAcquire() {
        if (unlimited) {
            return true;
        }
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
            lastRefillNanos = now;
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Calls that may be made back to back when the bucket is full. */
    int burst() {
        return unlimited ? Integer.MAX_VALUE : (int) capacity;
    }
}
