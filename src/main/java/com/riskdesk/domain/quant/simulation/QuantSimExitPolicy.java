package com.riskdesk.domain.quant.simulation;

/**
 * How the Quant 7-Gates harness treats a pattern flip to {@code AVOID} while a
 * simulated trade is open.
 *
 * <p>Calibration on the first 863 recorded trades (2026-06-02 → 2026-06-11)
 * showed the legacy immediate-AVOID exit closed 79% of trades within a median
 * of 2 minutes at a median of -0.6 pts — pure churn that destroyed the edge
 * the SL/TP plan would otherwise have captured. See
 * {@code docs/AI_HANDOFF.md} ("Quant 7-Gates exit recalibration").
 */
public enum QuantSimExitPolicy {

    /** Legacy behaviour — close immediately on the first AVOID scan. */
    FLOW_AVOID,

    /**
     * Honour an AVOID flip only when the trade is in profit at that scan;
     * a losing trade rides on to its SL/TP plan instead of locking the loss.
     */
    FLOW_AVOID_IN_PROFIT,

    /** Ignore AVOID flips entirely — trades resolve at SL, TP or EOD flat. */
    SLTP_ONLY
}
