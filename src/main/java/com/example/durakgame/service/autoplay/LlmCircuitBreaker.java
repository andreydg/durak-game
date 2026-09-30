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
 * back is replaced after one more cooldown.
 *
 * <p>Each permit carries the generation it was granted in, and the generation moves on whenever the
 * breaker opens, lets a probe through or closes. Only results from the current generation count: a
 * call that was still in flight when the breaker opened, or a probe that has since been replaced, can
 * neither close the breaker early nor re-open it. Thread-safe; critical sections are tiny.
 */
final class LlmCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(LlmCircuitBreaker.class);

    enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /** What {@link #tryAcquire()} allowed. */
    enum Kind {
        CALL,
        PROBE,
        REJECTED
    }

    /** A {@link Kind} stamped with the generation it was granted in; hand it back to {@link #record}. */
    record Permit(Kind kind, long generation) {
        boolean rejected() {
            return kind == Kind.REJECTED;
        }
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

    private static final Permit REJECTED = new Permit(Kind.REJECTED, -1);

    private final int failureThreshold;
    private final long cooldownNanos;
    private final LongSupplier nanoClock;

    private State state = State.CLOSED;
    private long generation;
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
            return new Permit(Kind.CALL, generation);
        }
        long now = nanoClock.getAsLong();
        if (state == State.OPEN) {
            if (now - openedAtNanos < cooldownNanos) {
                return REJECTED;
            }
            state = State.HALF_OPEN;
            probeInFlight = false;
            log.info("autoplay_circuit_breaker state=half_open consecutiveFailures={}", consecutiveFailures);
        }
        if (probeInFlight && now - probeStartedAtNanos < cooldownNanos) {
            return REJECTED;
        }
        probeInFlight = true;
        probeStartedAtNanos = now;
        return new Permit(Kind.PROBE, ++generation);
    }

    synchronized void record(Permit permit, Outcome outcome) {
        if (!enabled() || permit == null || permit.rejected() || permit.generation() != generation) {
            return;
        }
        // Nothing is granted while open, so a current permit is a call while closed or the probe while half-open.
        switch (outcome) {
            case SUCCESS -> {
                if (state == State.HALF_OPEN) {
                    log.info("autoplay_circuit_breaker state=closed consecutiveFailures={}", consecutiveFailures);
                    state = State.CLOSED;
                    probeInFlight = false;
                    generation++;
                }
                consecutiveFailures = 0;
            }
            case FAILURE -> {
                consecutiveFailures++;
                if (state == State.HALF_OPEN) {
                    open("probe_failed");
                } else if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
                    open("consecutive_failures");
                }
            }
            case NEUTRAL -> {
                if (state == State.HALF_OPEN) {
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
        generation++;
        openedAtNanos = nanoClock.getAsLong();
        probeInFlight = false;
        log.warn("autoplay_circuit_breaker state=open cause={} consecutiveFailures={} cooldownMs={}",
                cause, consecutiveFailures, cooldownNanos / 1_000_000);
    }
}
