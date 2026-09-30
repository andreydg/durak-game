package com.example.durakgame.service.autoplay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Stops calling the model after repeated transient failures so bot turns fall back to the heuristic
 * immediately instead of each waiting out a timeout.
 *
 * <p>Closed: every call goes through. After {@code failureThreshold} consecutive failures (timeouts,
 * I/O errors, HTTP 429/5xx) it opens for {@code cooldown}. When the cooldown has passed, exactly one
 * probe call is let through (half-open) while everything else keeps being rejected: a successful
 * probe closes the breaker, a failed one re-opens it for another cooldown. A probe that never reports
 * back is replaced after one more cooldown. Thread-safe; critical sections are tiny.
 */
final class LlmCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreaker.class);

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /** What {@link #tryAcquire()} granted; hand it back to {@link #record}. */
    enum Permit {
        CALL,
        PROBE,
        REJECTED
    }

    /** How a permitted call ended, from the breaker's point of view. */
    enum Outcome {
        /** The service answered (any status that is not 429/5xx). */
        SUCCESS,
        /** Timeout, I/O error, HTTP 429 or 5xx. */
        FAILURE,
        /** Says nothing about the service (local error, interrupted, call skipped after acquiring). */
        NEUTRAL
    }

    private final int failureThreshold;
    private final long cooldownNanos;
    private final LongSupplier nanoClock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtNanos;
    private boolean probeInFlight;
    private long probeStartedAtNanos;

    /** A {@code failureThreshold} below 1 disables the breaker. */
    LlmCircuitBreaker(int failureThreshold, Duration cooldown, LongSupplier nanoClock) {
        this.failureThreshold = failureThreshold;
        this.cooldownNanos = Math.max(0, cooldown.toNanos());
        this.nanoClock = nanoClock;
    }

    boolean enabled() {
        return failureThreshold > 0;
    }

    synchronized Permit tryAcquire() {
        if (!enabled() || state == State.CLOSED) {
            return Permit.CALL;
        }
        long now = nanoClock.getAsLong();
        if (state == State.OPEN) {
            if (now - openedAtNanos < cooldownNanos) {
                return Permit.REJECTED;
            }
            state = State.HALF_OPEN;
            probeInFlight = false;
            log.info("autoplay_circuit_breaker state=half_open consecutiveFailures={}", consecutiveFailures);
        }
        if (probeInFlight && now - probeStartedAtNanos < cooldownNanos) {
            return Permit.REJECTED;
        }
        probeInFlight = true;
        probeStartedAtNanos = now;
        return Permit.PROBE;
    }

    synchronized void record(Permit permit, Outcome outcome) {
        if (!enabled() || permit == null || permit == Permit.REJECTED) {
            return;
        }
        switch (outcome) {
            case SUCCESS -> {
                if (state != State.CLOSED) {
                    log.info("autoplay_circuit_breaker state=closed consecutiveFailures={}", consecutiveFailures);
                }
                state = State.CLOSED;
                consecutiveFailures = 0;
                probeInFlight = false;
            }
            case FAILURE -> {
                consecutiveFailures++;
                if (state == State.HALF_OPEN && permit == Permit.PROBE) {
                    open("probe_failed");
                } else if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
                    open("consecutive_failures");
                }
            }
            case NEUTRAL -> {
                if (state == State.HALF_OPEN && permit == Permit.PROBE) {
                    probeInFlight = false;
                }
            }
        }
    }

    synchronized State state() {
        return state;
    }

    private void open(String cause) {
        state = State.OPEN;
        openedAtNanos = nanoClock.getAsLong();
        probeInFlight = false;
        log.warn("autoplay_circuit_breaker state=open cause={} consecutiveFailures={} cooldownMs={}",
                cause, consecutiveFailures, cooldownNanos / 1_000_000);
    }
}
