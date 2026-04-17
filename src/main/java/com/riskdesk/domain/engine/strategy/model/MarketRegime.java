package com.riskdesk.domain.engine.strategy.model;

/**
 * Market regime classification. Maps onto the existing
 * {@link com.riskdesk.domain.engine.indicators.MarketRegimeDetector} string constants.
 *
 * <p>TRENDING collapses TRENDING_UP and TRENDING_DOWN from the detector because the
 * directional component lives separately on {@link MacroBias}. The regime only tells
 * us <i>how</i> price moves, not <i>where</i>.
 */
public enum MarketRegime {
    TRENDING,
    RANGING,
    CHOPPY,
    UNKNOWN;

    public static MarketRegime fromDetectorLabel(String label) {
        if (label == null) return UNKNOWN;
        return switch (label) {
            case "TRENDING_UP", "TRENDING_DOWN" -> TRENDING;
            case "RANGING" -> RANGING;
            case "CHOPPY"  -> CHOPPY;
            default        -> UNKNOWN;
        };
    }
}
