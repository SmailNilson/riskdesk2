package com.riskdesk.domain.engine.strategy.wtxrsi;

/**
 * How strictly the WaveTrend cross must relate to the OB/OS zone.
 *
 * Switchable per profile so we can A/B the three classical interpretations
 * of "le WT croise dans la zone de survente".
 */
public enum WtxRsiZoneMode {
    /**
     * The cross bar's WT1 must itself sit inside the band
     * (LONG: WT1 ≤ oversold, SHORT: WT1 ≥ overbought).
     * LazyBear literal — strictest variant.
     */
    STRICT_ZONE,

    /**
     * The cross can occur anywhere, but the band must have been visited
     * within the last {@code zoneLookbackBars}. Useful when you want to
     * catch the first bounce after WT has already left the extreme.
     */
    VISITED_RECENTLY,

    /**
     * The cross bar's WT1 must be inside the band <i>or</i> the previous
     * WT1 (one bar before the cross) was inside the band. Effectively
     * accepts a 1-bar lag between "exited zone" and "crossed".
     */
    CROSS_FROM_ZONE
}
