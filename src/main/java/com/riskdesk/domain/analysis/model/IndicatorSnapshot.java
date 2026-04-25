package com.riskdesk.domain.analysis.model;

import java.math.BigDecimal;

/**
 * Frozen indicator readings used by the scoring engine.
 * <p>
 * All fields are nullable — depending on data availability some indicators may
 * not be computable yet (e.g. EMA200 needs 200 bars).
 */
public record IndicatorSnapshot(
    BigDecimal lastPrice,
    Double rsi,
    String rsiSignal,
    Double macdHistogram,
    Boolean supertrendBullish,
    Double vwap,
    Double cmf,
    Double bbPct,
    Boolean bbExpanding,
    Double stochK,
    Double stochD,
    String stochCrossover,
    Double wt1,
    Double wt2
) {
}
