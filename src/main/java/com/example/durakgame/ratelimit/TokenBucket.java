package com.example.durakgame.ratelimit;

/**
 * A classic token bucket: up to {@code capacity} tokens, refilled at {@code refillPerSecond}.
 * Each permitted request consumes one token. Thread-safe via coarse synchronization (buckets are
 * per-IP, so contention is low).
 */
public final class TokenBucket {
    private final double capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System.nanoTime());
    }

    TokenBucket(double capacity, double refillPerSecond, long initialNanos) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = initialNanos;
    }

    public synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public boolean tryConsume() {
        return tryConsume(System.nanoTime());
    }

    /** Nanos since the last consumed token, used to evict idle buckets. */
    public synchronized long idleNanos(long nowNanos) {
        return nowNanos - lastRefillNanos;
    }

    private void refill(long nowNanos) {
        double elapsedSeconds = (nowNanos - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefillNanos = nowNanos;
        }
    }
}
