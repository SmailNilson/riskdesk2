package com.riskdesk.application.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine tests for {@link GeminiCircuitBreaker}. Covers CLOSED → OPEN
 * transition on repeated failures, OPEN short-circuit, cool-down transition to
 * HALF_OPEN, and HALF_OPEN trial outcomes.
 */
class GeminiCircuitBreakerTest {

    @Test
    void startsClosed_allowsRequests() {
        GeminiCircuitBreaker cb = new GeminiCircuitBreaker(3, Duration.ofSeconds(1));
        assertEquals(GeminiCircuitBreaker.State.CLOSED, cb.currentState());
        assertTrue(cb.allowRequest());
    }

    @Test
    void opensAfterFailureThreshold() {
        GeminiCircuitBreaker cb = new GeminiCircuitBreaker(3, Duration.ofSeconds(1));
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(GeminiCircuitBreaker.State.CLOSED, cb.currentState());
        cb.recordFailure();
        assertEquals(GeminiCircuitBreaker.State.OPEN, cb.currentState());
        assertFalse(cb.allowRequest(), "OPEN breaker must short-circuit");
    }

    @Test
    void closedAgain_afterSuccess() {
        GeminiCircuitBreaker cb = new GeminiCircuitBreaker(3, Duration.ofSeconds(1));
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess(); // resets counter
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(GeminiCircuitBreaker.State.CLOSED, cb.currentState(),
            "2 failures < threshold after reset should stay CLOSED");
    }

    @Test
    void halfOpen_onCooldown_trialCall() throws Exception {
        GeminiCircuitBreaker cb = new GeminiCircuitBreaker(1, Duration.ofMillis(50));
        cb.recordFailure();
        assertEquals(GeminiCircuitBreaker.State.OPEN, cb.currentState());
        assertFalse(cb.allowRequest());

        Thread.sleep(80); // cool-down elapses

        // Next allowRequest transitions OPEN → HALF_OPEN and returns true
        assertTrue(cb.allowRequest(), "after cool-down the breaker must allow a probe");
        assertEquals(GeminiCircuitBreaker.State.HALF_OPEN, cb.currentState());
    }

    @Test
    void halfOpen_failure_reopensImmediately() throws Exception {
        GeminiCircuitBreaker cb = new GeminiCircuitBreaker(1, Duration.ofMillis(30));
        cb.recordFailure();
        Thread.sleep(60);
        cb.allowRequest();                  // → HALF_OPEN
        cb.recordFailure();                 // trial failed
        assertEquals(GeminiCircuitBreaker.State.OPEN, cb.currentState());
    }

    @Test
    void halfOpen_success_closesBreaker() throws Exception {
        GeminiCircuitBreaker cb = new GeminiCircuitBreaker(1, Duration.ofMillis(30));
        cb.recordFailure();
        Thread.sleep(60);
        cb.allowRequest();                  // → HALF_OPEN
        cb.recordSuccess();                 // trial passed
        assertEquals(GeminiCircuitBreaker.State.CLOSED, cb.currentState());
        assertEquals(0, cb.consecutiveFailures());
    }

    @Test
    void invalidConfig_rejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new GeminiCircuitBreaker(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
            () -> new GeminiCircuitBreaker(3, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
            () -> new GeminiCircuitBreaker(3, null));
    }
}
