package com.riskdesk.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal circuit breaker for {@link GeminiAgentAdapter}.
 *
 * <p>When Gemini starts failing (timeout, 5xx, parse error), each of the three
 * AI agents still pays the full HTTP read-timeout per call — which can easily
 * blow past the orchestrator's 20 s budget and cascade across instruments.
 * This breaker short-circuits to the "fallback" path after N consecutive
 * failures, then probes again after a cool-down.
 *
 * <p>State machine:
 * <ul>
 *   <li>{@code CLOSED} — normal. Calls pass through; failures are counted.</li>
 *   <li>{@code OPEN} — all calls return fallback immediately for
 *       {@code openDuration}. Requires no synchronization with the HTTP client.</li>
 *   <li>{@code HALF_OPEN} — cool-down elapsed; the next call is a trial.
 *       Success → back to {@code CLOSED}; failure → back to {@code OPEN}.</li>
 * </ul>
 *
 * <p>Chose a tiny bespoke implementation over Resilience4j to avoid pulling in
 * {@code resilience4j-spring-boot3} (6+ transitive deps) for a 50-line state
 * machine the agent stack doesn't need anywhere else.
 *
 * <p>Thread-safe: state mutations use {@link AtomicReference}/{@link AtomicInteger};
 * the transition from {@code OPEN} to {@code HALF_OPEN} is lazy (checked on each
 * call) so we don't need a background scheduler.
 */
public class GeminiCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(GeminiCircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    public GeminiCircuitBreaker(int failureThreshold, Duration openDuration) {
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be >= 1");
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    /**
     * Returns {@code true} if a call may proceed. If the breaker is OPEN but the
     * cool-down has elapsed, transitions to HALF_OPEN and lets one trial call through.
     */
    public boolean allowRequest() {
        State s = state.get();
        if (s == State.CLOSED || s == State.HALF_OPEN) return true;
        // OPEN — check whether cool-down has elapsed
        Instant opened = openedAt.get();
        if (opened != null && Instant.now().isAfter(opened.plus(openDuration))) {
            // Transition OPEN → HALF_OPEN. Only one thread wins the CAS.
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                log.info("Circuit breaker: OPEN → HALF_OPEN (probing after {}s)",
                    openDuration.toSeconds());
            }
            return true;
        }
        return false;
    }

    /** Records a successful call. Closes the breaker and resets counters. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        State prev = state.getAndSet(State.CLOSED);
        if (prev != State.CLOSED) {
            log.info("Circuit breaker: {} → CLOSED (call succeeded)", prev);
        }
    }

    /**
     * Records a failed call. In HALF_OPEN the breaker reopens immediately; in
     * CLOSED it opens once the consecutive-failure threshold is crossed.
     */
    public void recordFailure() {
        State s = state.get();
        if (s == State.HALF_OPEN) {
            open();
            return;
        }
        int fails = consecutiveFailures.incrementAndGet();
        if (fails >= failureThreshold && s == State.CLOSED) {
            open();
        }
    }

    private void open() {
        if (state.getAndSet(State.OPEN) != State.OPEN) {
            openedAt.set(Instant.now());
            log.warn("Circuit breaker: OPEN for {}s after {} consecutive failures",
                openDuration.toSeconds(), consecutiveFailures.get());
        }
    }

    public State currentState() { return state.get(); }

    public int consecutiveFailures() { return consecutiveFailures.get(); }
}
