package com.example.durakgame.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {

    @Test
    void allowsUpToCapacityThenRejects() {
        long t0 = 0L;
        TokenBucket bucket = new TokenBucket(3, 1, t0);
        assertTrue(bucket.tryConsume(t0));
        assertTrue(bucket.tryConsume(t0));
        assertTrue(bucket.tryConsume(t0));
        assertFalse(bucket.tryConsume(t0), "4th request in the same instant must be rejected");
    }

    @Test
    void refillsOverTime() {
        long t0 = 0L;
        TokenBucket bucket = new TokenBucket(2, 10, t0); // 10 tokens/sec
        assertTrue(bucket.tryConsume(t0));
        assertTrue(bucket.tryConsume(t0));
        assertFalse(bucket.tryConsume(t0));

        // 200ms later -> 2 tokens refilled (10/sec * 0.2s), capped at capacity.
        long t1 = t0 + 200_000_000L;
        assertTrue(bucket.tryConsume(t1));
        assertTrue(bucket.tryConsume(t1));
        assertFalse(bucket.tryConsume(t1));
    }

    @Test
    void neverExceedsCapacityOnRefill() {
        TokenBucket bucket = new TokenBucket(2, 100, 0L);
        // Long idle period: tokens cap at capacity, not unbounded.
        long far = 10_000_000_000L;
        assertTrue(bucket.tryConsume(far));
        assertTrue(bucket.tryConsume(far));
        assertFalse(bucket.tryConsume(far));
    }
}
