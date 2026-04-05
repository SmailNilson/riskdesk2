package com.riskdesk.domain.engine.correlation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CrossInstrumentCorrelationEngine}.
 *
 * <p>Test matrix covers:
 * <ul>
 *   <li>Happy path: full IDLE → MCL_TRIGGERED → CONFIRMED signal cycle</li>
 *   <li>MNQ rejection without prior MCL trigger (IDLE guard)</li>
 *   <li>MCL breakout but no MNQ confirmation — timeout reset to IDLE</li>
 *   <li>MNQ rejection arrives AFTER the 10-minute window (late confirmation rejected)</li>
 *   <li>Concurrent-style re-trigger: second MCL breakout is ignored while MCL_TRIGGERED</li>
 *   <li>Force reset always returns engine to IDLE</li>
 *   <li>Signal payload correctness (lag, prices, instruments)</li>
 * </ul>
 */
@DisplayName("CrossInstrumentCorrelationEngine — ONIMS state machine")
class CrossInstrumentCorrelationEngineTest {

    private CrossInstrumentCorrelationEngine engine;

    private static final Instant T0      = Instant.parse("2026-04-05T14:30:00Z"); // 10:30 ET
    private static final BigDecimal MCL_BREAKOUT    = new BigDecimal("112.75");
    private static final BigDecimal MCL_RESISTANCE  = new BigDecimal("112.50");
    private static final BigDecimal MNQ_VWAP        = new BigDecimal("24100.00");
    private static final BigDecimal MNQ_CLOSE_BELOW = new BigDecimal("24085.00"); // < VWAP

    @BeforeEach
    void setUp() {
        engine = new CrossInstrumentCorrelationEngine();
    }

    // -----------------------------------------------------------------------
    // Initial state
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Engine starts in IDLE state")
    void startsIdle() {
        assertThat(engine.currentState()).isEqualTo(CorrelationState.IDLE);
        assertThat(engine.mclTriggerAt()).isNull();
        assertThat(engine.mclBreakoutPrice()).isNull();
    }

    // -----------------------------------------------------------------------
    // MCL breakout transition
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("MCL breakout from IDLE transitions to MCL_TRIGGERED")
    void mclBreakoutFromIdleTriggersState() {
        boolean result = engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);

        assertThat(result).isTrue();
        assertThat(engine.currentState()).isEqualTo(CorrelationState.MCL_TRIGGERED);
        assertThat(engine.mclTriggerAt()).isEqualTo(T0);
        assertThat(engine.mclBreakoutPrice()).isEqualByComparingTo(MCL_BREAKOUT);
        assertThat(engine.mclResistanceLevel()).isEqualByComparingTo(MCL_RESISTANCE);
    }

    @Test
    @DisplayName("Second MCL breakout is ignored when already MCL_TRIGGERED")
    void secondMclBreakoutIgnoredWhenTriggered() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);

        // Simulate second breakout 2 minutes later — should be ignored
        Instant t2 = T0.plus(2, ChronoUnit.MINUTES);
        boolean result = engine.onMclBreakout(t2, new BigDecimal("113.00"), new BigDecimal("112.80"));

        assertThat(result).isFalse();
        // Original trigger time must be preserved
        assertThat(engine.mclTriggerAt()).isEqualTo(T0);
        assertThat(engine.mclBreakoutPrice()).isEqualByComparingTo(MCL_BREAKOUT);
    }

    // -----------------------------------------------------------------------
    // MNQ rejection without MCL trigger — IDLE guard
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("MNQ VWAP rejection without prior MCL breakout returns empty (IDLE guard)")
    void mnqRejectionWithoutMclTriggerReturnsEmpty() {
        assertThat(engine.currentState()).isEqualTo(CorrelationState.IDLE);

        Optional<CrossInstrumentSignal> signal =
                engine.onMnqVwapRejection(T0, MNQ_VWAP, MNQ_CLOSE_BELOW);

        assertThat(signal).isEmpty();
        assertThat(engine.currentState()).isEqualTo(CorrelationState.IDLE);
    }

    // -----------------------------------------------------------------------
    // Full signal cycle — happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Full cycle: MCL breakout + MNQ VWAP rejection within window emits signal")
    void fullSignalCycleConfirmsSignal() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);

        // MNQ rejects VWAP 5 minutes later (within 10-minute window)
        Instant t5min = T0.plus(5, ChronoUnit.MINUTES);
        Optional<CrossInstrumentSignal> signal =
                engine.onMnqVwapRejection(t5min, MNQ_VWAP, MNQ_CLOSE_BELOW);

        assertThat(signal).isPresent();
        CrossInstrumentSignal s = signal.get();
        assertThat(s.leaderInstrument()).isEqualTo("MCL");
        assertThat(s.followerInstrument()).isEqualTo("MNQ");
        assertThat(s.leaderBreakoutPrice()).isEqualByComparingTo(MCL_BREAKOUT);
        assertThat(s.leaderResistanceLevel()).isEqualByComparingTo(MCL_RESISTANCE);
        assertThat(s.followerVwap()).isEqualByComparingTo(MNQ_VWAP);
        assertThat(s.followerClosePrice()).isEqualByComparingTo(MNQ_CLOSE_BELOW);
        assertThat(s.lagSeconds()).isEqualTo(300L); // 5 minutes = 300 seconds
        assertThat(s.confirmedAt()).isEqualTo(t5min);
    }

    @Test
    @DisplayName("Engine resets to IDLE after emitting a confirmed signal")
    void engineResetsAfterConfirmation() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);
        engine.onMnqVwapRejection(T0.plus(5, ChronoUnit.MINUTES), MNQ_VWAP, MNQ_CLOSE_BELOW);

        // Engine must be ready for the next setup
        assertThat(engine.currentState()).isEqualTo(CorrelationState.IDLE);
        assertThat(engine.mclTriggerAt()).isNull();
    }

    // -----------------------------------------------------------------------
    // Timeout: MCL trigger expires without MNQ confirmation
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("MCL trigger expires after 10-minute window — engine resets to IDLE")
    void mclTriggerExpiresAfterWindow() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);
        assertThat(engine.currentState()).isEqualTo(CorrelationState.MCL_TRIGGERED);

        // Simulate 11 minutes passing
        Instant t11min = T0.plus(11, ChronoUnit.MINUTES);
        boolean timedOut = engine.checkTimeout(t11min);

        assertThat(timedOut).isTrue();
        assertThat(engine.currentState()).isEqualTo(CorrelationState.IDLE);
        assertThat(engine.mclTriggerAt()).isNull();
    }

    @Test
    @DisplayName("checkTimeout before window expires does nothing")
    void checkTimeoutBeforeWindowExpiresDoesNothing() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);

        // Only 4 minutes have passed — within 10-minute window
        boolean timedOut = engine.checkTimeout(T0.plus(4, ChronoUnit.MINUTES));

        assertThat(timedOut).isFalse();
        assertThat(engine.currentState()).isEqualTo(CorrelationState.MCL_TRIGGERED);
    }

    @Test
    @DisplayName("checkTimeout on IDLE engine returns false and causes no state change")
    void checkTimeoutOnIdleDoesNothing() {
        boolean result = engine.checkTimeout(T0.plus(30, ChronoUnit.MINUTES));

        assertThat(result).isFalse();
        assertThat(engine.currentState()).isEqualTo(CorrelationState.IDLE);
    }

    // -----------------------------------------------------------------------
    // Late MNQ rejection — after correlation window
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("MNQ rejection arriving after 10-minute window is rejected and engine resets")
    void lateRejectionAfterWindowIsDiscarded() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);

        // 11 minutes later — outside the window
        Instant t11min = T0.plus(11, ChronoUnit.MINUTES);
        Optional<CrossInstrumentSignal> signal =
                engine.onMnqVwapRejection(t11min, MNQ_VWAP, MNQ_CLOSE_BELOW);

        assertThat(signal).isEmpty();
        // Engine should have reset to IDLE after detecting the expired window
        assertThat(engine.currentState()).isEqualTo(CorrelationState.IDLE);
    }

    // -----------------------------------------------------------------------
    // Exact boundary: 10 minutes (edge of window)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("MNQ rejection exactly at 10-minute boundary is accepted (inclusive upper bound)")
    void rejectionAtExactBoundaryIsAccepted() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);

        // Exactly 600 seconds = 10 minutes — window is inclusive (candle 2 of the 5m series).
        // T0+5m is candle 1, T0+10m is candle 2: both are within the "2-candle window".
        Instant t10min = T0.plus(10, ChronoUnit.MINUTES);
        Optional<CrossInstrumentSignal> signal =
                engine.onMnqVwapRejection(t10min, MNQ_VWAP, MNQ_CLOSE_BELOW);

        assertThat(signal).isPresent();
        assertThat(signal.get().lagSeconds()).isEqualTo(600L);
    }

    @Test
    @DisplayName("MNQ rejection at 9 minutes 59 seconds is accepted (within window)")
    void rejectionJustBeforeBoundaryIsAccepted() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);

        Instant t599s = T0.plus(599, ChronoUnit.SECONDS);
        Optional<CrossInstrumentSignal> signal =
                engine.onMnqVwapRejection(t599s, MNQ_VWAP, MNQ_CLOSE_BELOW);

        assertThat(signal).isPresent();
        assertThat(signal.get().lagSeconds()).isEqualTo(599L);
    }

    // -----------------------------------------------------------------------
    // Force reset
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("forceReset always returns engine to IDLE regardless of current state")
    void forceResetAlwaysReturnsToIdle() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);
        assertThat(engine.currentState()).isEqualTo(CorrelationState.MCL_TRIGGERED);

        engine.forceReset();

        assertThat(engine.currentState()).isEqualTo(CorrelationState.IDLE);
        assertThat(engine.mclTriggerAt()).isNull();
        assertThat(engine.mclBreakoutPrice()).isNull();
        assertThat(engine.mclResistanceLevel()).isNull();
    }

    // -----------------------------------------------------------------------
    // Re-usability after reset
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Engine accepts new MCL breakout after forceReset")
    void engineAcceptsNewBreakoutAfterReset() {
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);
        engine.forceReset();

        Instant t2 = T0.plus(15, ChronoUnit.MINUTES);
        BigDecimal newBreakout    = new BigDecimal("113.50");
        BigDecimal newResistance  = new BigDecimal("113.00");
        boolean triggered = engine.onMclBreakout(t2, newBreakout, newResistance);

        assertThat(triggered).isTrue();
        assertThat(engine.currentState()).isEqualTo(CorrelationState.MCL_TRIGGERED);
        assertThat(engine.mclBreakoutPrice()).isEqualByComparingTo(newBreakout);
    }

    @Test
    @DisplayName("Engine can produce a second signal after first confirmation cycle completes")
    void twoConsecutiveSignalCycles() {
        // First cycle
        engine.onMclBreakout(T0, MCL_BREAKOUT, MCL_RESISTANCE);
        Optional<CrossInstrumentSignal> first =
                engine.onMnqVwapRejection(T0.plus(5, ChronoUnit.MINUTES), MNQ_VWAP, MNQ_CLOSE_BELOW);
        assertThat(first).isPresent();
        assertThat(engine.currentState()).isEqualTo(CorrelationState.IDLE);

        // Second cycle — 30 minutes later
        Instant t2 = T0.plus(30, ChronoUnit.MINUTES);
        BigDecimal newBreakout   = new BigDecimal("114.00");
        BigDecimal newResistance = new BigDecimal("113.75");
        engine.onMclBreakout(t2, newBreakout, newResistance);

        Optional<CrossInstrumentSignal> second =
                engine.onMnqVwapRejection(t2.plus(3, ChronoUnit.MINUTES),
                        new BigDecimal("24050.00"), new BigDecimal("24035.00"));

        assertThat(second).isPresent();
        assertThat(second.get().leaderBreakoutPrice()).isEqualByComparingTo(newBreakout);
    }
}
