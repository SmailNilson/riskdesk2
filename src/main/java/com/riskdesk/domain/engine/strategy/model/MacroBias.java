package com.riskdesk.domain.engine.strategy.model;

/**
 * High-timeframe directional bias derived from SMC swing structure.
 *
 * <p>NEUTRAL is a first-class value — not an absence. It means "HTF has no clear bias",
 * which disqualifies a trend-following setup like SBDR but does not block a range
 * reversal setup like LSAR.
 */
public enum MacroBias {
    BULL,
    BEAR,
    NEUTRAL;

    public static MacroBias fromSwingBias(String swingBias) {
        if (swingBias == null || swingBias.isBlank()) return NEUTRAL;
        if ("BULLISH".equalsIgnoreCase(swingBias)) return BULL;
        if ("BEARISH".equalsIgnoreCase(swingBias)) return BEAR;
        return NEUTRAL;
    }
}
