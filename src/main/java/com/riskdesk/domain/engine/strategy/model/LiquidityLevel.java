package com.riskdesk.domain.engine.strategy.model;

import java.math.BigDecimal;

/**
 * Resting-liquidity landmark (equal-highs or equal-lows cluster, or HTF swing point).
 *
 * @param high true if the level is above current price (sell-side liquidity = stops of
 *             longs resting at the equal-highs). false for equal-lows (buy-side).
 * @param touchCount number of bars that have tagged this level — proxy for stop density
 */
public record LiquidityLevel(
    BigDecimal price,
    boolean high,
    int touchCount
) {
    public LiquidityLevel {
        if (price == null) throw new IllegalArgumentException("LiquidityLevel price must not be null");
        if (touchCount < 0) throw new IllegalArgumentException("touchCount must be >= 0");
    }
}
