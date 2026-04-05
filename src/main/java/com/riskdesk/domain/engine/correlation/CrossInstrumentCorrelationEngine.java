package com.riskdesk.domain.engine.correlation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe state machine for the Oil-Nasdaq Inverse Momentum Scalp (ONIMS) strategy.
 *
 * <h2>Strategy Rationale</h2>
 * In risk-off / stagflationary regimes (VIX &gt; 20, WTI oil shock), MCL reacts to
 * geopolitical news before MNQ. The lag between an MCL channel breakout and the subsequent
 * MNQ VWAP rejection is a statistically exploitable inefficiency. This engine detects that
 * cross-instrument sequence and emits a {@link CrossInstrumentSignal} when both conditions
 * align within the correlation window.
 *
 * <h2>State Machine</h2>
 * <pre>
 *   IDLE ──(onMclBreakout)──► MCL_TRIGGERED ──(onMnqVwapRejection within window)──► CONFIRMED
 *                                  │
 *                                  └──(checkTimeout, window exceeded)──► IDLE
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>MCL and MNQ emit hundreds of ticks per second on separate threads. This class uses:
 * <ul>
 *   <li>{@link AtomicReference} for the state — all transitions are CAS-based and atomic.</li>
 *   <li>{@code volatile} for scalar fields read/written across threads
 *       ({@code mclTriggerAt}, {@code mclBreakoutPrice}, {@code mclResistanceLevel}).</li>
 * </ul>
 * No {@code synchronized} blocks are used, keeping throughput high under load.
 *
 * <h2>Domain Purity</h2>
 * This class is pure domain logic — no Spring annotations, no JPA, no I/O.
 * All orchestration (loading candles, applying session/VIX filters, publishing events)
 * lives in the application layer ({@code CrossInstrumentAlertService}).
 */
public class CrossInstrumentCorrelationEngine {

    /**
     * Maximum delay allowed between the MCL breakout signal and the MNQ VWAP rejection
     * for the two conditions to be considered part of the same correlated move.
     * Two 5-minute candles = 10 minutes.
     */
    private static final Duration CORRELATION_WINDOW = Duration.ofMinutes(10);

    // -----------------------------------------------------------------------
    // State — all transitions are atomic
    // -----------------------------------------------------------------------

    private final AtomicReference<CorrelationState> state =
            new AtomicReference<>(CorrelationState.IDLE);

    /** UTC instant when the MCL breakout was detected. {@code null} when IDLE. */
    private volatile Instant mclTriggerAt;

    /** MCL close price at the breakout candle. */
    private volatile BigDecimal mclBreakoutPrice;

    /** The N-period resistance high that MCL broke above. */
    private volatile BigDecimal mclResistanceLevel;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Records an MCL channel breakout. Transitions the engine from {@link CorrelationState#IDLE}
     * to {@link CorrelationState#MCL_TRIGGERED} if and only if the engine is currently idle.
     *
     * <p>If the engine is already in {@code MCL_TRIGGERED} (a previous breakout timed out
     * before confirmation), this call is ignored — the first unconfirmed trigger wins.
     *
     * @param detectedAt      UTC timestamp of the MCL breakout candle close
     * @param breakoutPrice   MCL 5m close that exceeded the resistance level
     * @param resistanceLevel the N-period high that was broken
     * @return {@code true} if the transition was successful (engine was IDLE)
     */
    public boolean onMclBreakout(Instant detectedAt, BigDecimal breakoutPrice, BigDecimal resistanceLevel) {
        if (state.compareAndSet(CorrelationState.IDLE, CorrelationState.MCL_TRIGGERED)) {
            mclTriggerAt       = detectedAt;
            mclBreakoutPrice   = breakoutPrice;
            mclResistanceLevel = resistanceLevel;
            return true;
        }
        return false;
    }

    /**
     * Checks whether a MNQ VWAP rejection completes the correlation pattern.
     *
     * <p>This method atomically checks that:
     * <ol>
     *   <li>The engine is in {@link CorrelationState#MCL_TRIGGERED}.</li>
     *   <li>The rejection occurs within {@value #CORRELATION_WINDOW} of the MCL trigger.</li>
     * </ol>
     * If both conditions hold, the engine transitions to {@link CorrelationState#CONFIRMED},
     * emits a {@link CrossInstrumentSignal}, and immediately resets to
     * {@link CorrelationState#IDLE} to be ready for the next setup.
     *
     * @param confirmedAt  UTC timestamp of the MNQ rejection candle close
     * @param vwap         VWAP value for MNQ at confirmation time
     * @param mnqClosePrice MNQ 5m close price (should be below VWAP for a valid rejection)
     * @return an {@link Optional} containing the signal if confirmed, {@link Optional#empty()} otherwise
     */
    public Optional<CrossInstrumentSignal> onMnqVwapRejection(Instant confirmedAt,
                                                               BigDecimal vwap,
                                                               BigDecimal mnqClosePrice) {
        // Atomically claim the MCL_TRIGGERED state
        if (!state.compareAndSet(CorrelationState.MCL_TRIGGERED, CorrelationState.CONFIRMED)) {
            return Optional.empty();
        }

        // Capture volatiles under the now-exclusive CONFIRMED ownership
        Instant triggerAt       = mclTriggerAt;
        BigDecimal breakoutPrice   = mclBreakoutPrice;
        BigDecimal resistanceLevel = mclResistanceLevel;

        // Safety: if the trigger timestamp was somehow cleared, abort
        if (triggerAt == null) {
            state.set(CorrelationState.IDLE);
            return Optional.empty();
        }

        // Verify the correlation window has not elapsed
        long lagSeconds = Duration.between(triggerAt, confirmedAt).toSeconds();
        if (lagSeconds < 0 || lagSeconds > CORRELATION_WINDOW.toSeconds()) {
            // Window exceeded — reset and discard
            resetToIdle();
            return Optional.empty();
        }

        // Build and emit the signal, then immediately reset to IDLE for the next setup
        CrossInstrumentSignal signal = new CrossInstrumentSignal(
                "MCL",
                "MNQ",
                breakoutPrice,
                resistanceLevel,
                vwap,
                mnqClosePrice,
                lagSeconds,
                confirmedAt
        );

        resetToIdle();
        return Optional.of(signal);
    }

    /**
     * Checks whether the MCL trigger has expired without confirmation and resets if so.
     * Should be called periodically (e.g. on every CandleClosed event) to prevent
     * stale {@code MCL_TRIGGERED} states from blocking future setups.
     *
     * @param now current UTC instant
     * @return {@code true} if the engine was reset due to timeout
     */
    public boolean checkTimeout(Instant now) {
        if (state.get() != CorrelationState.MCL_TRIGGERED) {
            return false;
        }
        Instant triggerAt = mclTriggerAt;
        if (triggerAt == null || Duration.between(triggerAt, now).compareTo(CORRELATION_WINDOW) > 0) {
            // CAS: only reset if still in MCL_TRIGGERED (another thread may have claimed CONFIRMED)
            if (state.compareAndSet(CorrelationState.MCL_TRIGGERED, CorrelationState.IDLE)) {
                clearVolatiles();
                return true;
            }
        }
        return false;
    }

    /**
     * Forcefully resets the engine to {@link CorrelationState#IDLE}.
     * Use for configuration changes or testing.
     */
    public void forceReset() {
        state.set(CorrelationState.IDLE);
        clearVolatiles();
    }

    /**
     * Returns the current state of the engine. Safe to call from any thread.
     */
    public CorrelationState currentState() {
        return state.get();
    }

    /**
     * Returns the UTC instant of the last MCL breakout trigger, or {@code null} if IDLE.
     */
    public Instant mclTriggerAt() {
        return mclTriggerAt;
    }

    /**
     * Returns the MCL breakout price that triggered the current (or last) MCL_TRIGGERED state.
     * Returns {@code null} when IDLE.
     */
    public BigDecimal mclBreakoutPrice() {
        return mclBreakoutPrice;
    }

    /**
     * Returns the resistance level broken by MCL, or {@code null} when IDLE.
     */
    public BigDecimal mclResistanceLevel() {
        return mclResistanceLevel;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void resetToIdle() {
        state.set(CorrelationState.IDLE);
        clearVolatiles();
    }

    private void clearVolatiles() {
        mclTriggerAt       = null;
        mclBreakoutPrice   = null;
        mclResistanceLevel = null;
    }
}
