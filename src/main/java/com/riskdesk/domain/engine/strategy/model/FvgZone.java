package com.riskdesk.domain.engine.strategy.model;

import java.math.BigDecimal;

/**
 * Fair Value Gap — imbalance zone between candle i−1 and i+1 (price left behind).
 *
 * @param bullish true when the gap is bullish (upside imbalance, support on retest)
 * @param filledPct 0.0..1.0 — how much of the gap has been mitigated. 0 = untouched,
 *                  1 = fully filled (no longer actionable).
 */
public record FvgZone(
    boolean bullish,
    BigDecimal top,
    BigDecimal bottom,
    double filledPct
) {
    public FvgZone {
        if (top == null || bottom == null) {
            throw new IllegalArgumentException("FvgZone top/bottom must not be null");
        }
        if (top.compareTo(bottom) < 0) {
            throw new IllegalArgumentException("FvgZone top must be >= bottom");
        }
        if (filledPct < 0.0 || filledPct > 1.0) {
            throw new IllegalArgumentException("FvgZone filledPct must be in [0, 1]");
        }
    }

    public boolean isActionable() {
        return filledPct < 0.5;
    }
}
