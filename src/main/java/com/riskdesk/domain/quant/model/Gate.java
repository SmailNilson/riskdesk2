package com.riskdesk.domain.quant.model;

/**
 * Identifies one of the seven deterministic gates evaluated by the Quant
 * order-flow setup detector. The gates were lifted verbatim from the
 * battle-tested {@code mnq_monitor_v3.py} script that detects high-quality
 * SHORT setups by combining absorption, distribution and delta signals.
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
    G6_LIVE_PUSH
}
