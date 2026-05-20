package com.riskdesk.domain.quant.model;

/**
 * Identifies one of the seven deterministic gates evaluated by the Quant
 * order-flow setup detector. The SHORT gates {@code G0..G6} were lifted
 * verbatim from the battle-tested {@code mnq_monitor_v3.py} script that
 * detects high-quality SHORT setups by combining absorption, distribution and
 * delta signals.
 *
 * <p>Since the LONG-symmetry slice the enum also carries seven mirrored
 * gates ({@code L0..L6}) used by the LONG track. Each mirror keeps the same
 * thresholds with the polarity flipped (e.g. {@link #L1_ABS_BULL} requires
 * BULL-dominant n8 and {@link #L3_DELTA_POS} requires Δ &gt; +100).</p>
 */
public enum Gate {
    /** Daily regime filter — bullish day (or repeated ABS BULL) suspends SHORT setups. */
    G0_REGIME,
    /** Coherent bearish absorption — n8 ≥ 8, BEAR-dominant, delta not strongly positive. */
    G1_ABS_BEAR,
    /** Distribution persistence — at least 2/3 recent DIST scans ≥ 60 % confidence (ACCU not counted). */
    G2_DIST_PUR,
    /** Spot delta below -100 (with optional descending-trend bonus). */
    G3_DELTA_NEG,
    /** Buy ratio strictly below 48 %. */
    G4_BUY_PCT_LOW,
    /** Conditional ACCUM threshold — blocks the setup when the latest ACCU confidence breaches the dynamic threshold. */
    G5_ACCU_THRESHOLD,
    /** Live push price source (real-time quote, not delayed snapshot). */
    G6_LIVE_PUSH,

    // ── LONG mirror (PR LONG-symmetry) ─────────────────────────────────────

    /** LONG mirror: bearish day (or repeated ABS BEAR) suspends LONG setups. */
    L0_REGIME,
    /** LONG mirror: coherent bullish absorption — n8 ≥ 8, BULL-dominant, delta not strongly negative. */
    L1_ABS_BULL,
    /** LONG mirror: accumulation persistence — at least 2/3 recent ACCU scans ≥ 60 %. */
    L2_ACCU_PUR,
    /** LONG mirror: spot delta above +100 (with optional ascending-trend bonus). */
    L3_DELTA_POS,
    /** LONG mirror: buy ratio strictly above 52 %. */
    L4_BUY_PCT_HIGH,
    /** LONG mirror: conditional DIST threshold — blocks LONG when the latest DIST confidence breaches the dynamic threshold. */
    L5_DIST_THRESHOLD,
    /** LONG mirror: live push price source. */
    L6_LIVE_PUSH
}
