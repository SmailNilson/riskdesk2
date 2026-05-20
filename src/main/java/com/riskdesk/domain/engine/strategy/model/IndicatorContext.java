package com.riskdesk.domain.engine.strategy.model;

import java.math.BigDecimal;

/**
 * Snapshot of secondary indicator values surfaced to CONTEXT-layer agents.
 *
 * <p>VWAP / Bollinger / CMF (Chaikin Money Flow) are computed elsewhere by
 * {@code IndicatorService} and made available here so that
 * {@link com.riskdesk.domain.engine.strategy.agent.context.VwapDistanceAgent},
 * {@link com.riskdesk.domain.engine.strategy.agent.context.BollingerPositionAgent}
 * and {@link com.riskdesk.domain.engine.strategy.agent.context.CmfFlowAgent}
 * can vote without coupling to infrastructure.
 *
 * <p>Every field is nullable — agents MUST guard via the {@code has*} helpers
 * and abstain when the underlying indicator is unavailable for the current
 * (instrument, timeframe). {@link #empty()} is the default sentinel.
 */
public record IndicatorContext(
    BigDecimal vwap,
    BigDecimal vwapLowerBand,
    BigDecimal vwapUpperBand,
    /** Bollinger %B — position within the bands, typically in [0.0, 1.0]. */
    BigDecimal bbPct,
    /** Bollinger band width in price points (upper - lower). */
    BigDecimal bbWidth,
    /** Chaikin Money Flow — bounded approximately to [-1, +1]. */
    BigDecimal cmf,
    /** Chaikin Oscillator — raw, unbounded. */
    BigDecimal chaikinOsc
) {

    private static final IndicatorContext EMPTY =
        new IndicatorContext(null, null, null, null, null, null, null);

    public static IndicatorContext empty() {
        return EMPTY;
    }

    public boolean hasVwap() {
        return vwap != null && vwapLowerBand != null && vwapUpperBand != null;
    }

    public boolean hasBollinger() {
        return bbPct != null;
    }

    public boolean hasCmf() {
        return cmf != null;
    }
}
