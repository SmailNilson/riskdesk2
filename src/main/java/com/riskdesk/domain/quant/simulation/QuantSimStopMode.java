package com.riskdesk.domain.quant.simulation;

/**
 * How the SL/TP offsets of a Quant 7-Gates simulated trade are sized.
 *
 * <p>{@link #FIXED} uses the historical 25/40/80-point constants from
 * {@code QuantSnapshot}. Those constants are MNQ-scaled: on MCL (~$65) and MGC
 * they are unreachable, so every MCL/MGC trade degenerated to a flow-AVOID
 * churn exit. {@link #ATR} sizes the offsets from the instrument's own 5m ATR
 * so the plan is meaningful on every instrument and adapts to the volatility
 * regime.
 */
public enum QuantSimStopMode {

    /** Fixed point offsets (legacy 25/40/80 — only sensible on MNQ). */
    FIXED,

    /** Offsets = multiplier × ATR(period) on the configured timeframe. */
    ATR
}
