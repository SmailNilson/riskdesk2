package com.riskdesk.domain.engine.correlation;

/**
 * State machine states for the Oil-Nasdaq Inverse Momentum Scalp (ONIMS) strategy.
 *
 * <pre>
 *   IDLE ──(MCL breakout)──► MCL_TRIGGERED ──(MNQ VWAP rejection within 10m)──► CONFIRMED
 *                                 │
 *                                 └──(timeout > 10m)──► IDLE
 * </pre>
 *
 * Transitions are always made atomically via {@link CrossInstrumentCorrelationEngine}.
 */
public enum CorrelationState {

    /**
     * No active signal — waiting for the leader (MCL) to break out.
     */
    IDLE,

    /**
     * MCL has broken above its N-period high with elevated volume.
     * Engine is now waiting for MNQ to confirm via VWAP rejection.
     * If confirmation does not arrive within the timeout window (10 minutes),
     * the engine resets to {@link #IDLE}.
     */
    MCL_TRIGGERED,

    /**
     * Both conditions met within the correlation window: MCL breakout + MNQ VWAP rejection.
     * A {@link CrossInstrumentSignal} has been published. Engine resets to {@link #IDLE}
     * immediately after emitting the signal to avoid re-firing on the same setup.
     */
    CONFIRMED
}
