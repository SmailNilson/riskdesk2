package com.riskdesk.domain.alert.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record IndicatorAlertSnapshot(
    String emaCrossover,
    BigDecimal rsi,
    String rsiSignal,
    String macdCrossover,
    String lastBreakType,
    String lastInternalBreakType,
    String lastSwingBreakType,
    BigDecimal wtWt1,
    String wtCrossover,
    String wtSignal,
    BigDecimal vwap,
    List<OrderBlockZone> activeOrderBlocks,
    /** UC-SMC-009: real OB lifecycle events (MITIGATION / INVALIDATION) on the last bar. */
    List<OrderBlockEvent> recentObEvents,
    /** Timestamp of the last closed candle used to compute this snapshot (Rule 4: candle close guard). */
    Instant lastCandleTimestamp,
    // ── V2 expansion fields ──────────────────────────────────────────────────
    /** Supertrend direction: "UPTREND" or "DOWNTREND". */
    String supertrendDirection,
    /** Bollinger BBTrend signal: "TRENDING" or "CONSOLIDATING". */
    String bbTrendSignal,
    /** Close price for VWAP and MTF level comparison. */
    BigDecimal close,
    /** Derived VWAP position: "ABOVE" or "BELOW". */
    String vwapPosition,
    /** FVG lifecycle events on the last bar. */
    List<FvgEvent> recentFvgEvents,
    /** Equal-level sweep events on the last bar. */
    List<SweepEvent> recentSweepEvents,
    /** Delta Flow bias: "BUYING", "SELLING", or "NEUTRAL". */
    String deltaFlowBias,
    /** Chaikin Oscillator zero-line crossover: "BULLISH_CROSS" or "BEARISH_CROSS". */
    String chaikinCrossover,
    /** MTF levels for level-cross alerts. */
    BigDecimal mtfDailyHigh,
    BigDecimal mtfDailyLow,
    BigDecimal mtfWeeklyHigh,
    BigDecimal mtfWeeklyLow,
    BigDecimal mtfMonthlyHigh,
    BigDecimal mtfMonthlyLow,
    /** Stochastic %K signal: "OVERSOLD", "OVERBOUGHT", "NEUTRAL". */
    String stochSignal,
    /** Stochastic %K/%D crossover: "BULLISH_CROSS" or "BEARISH_CROSS". */
    String stochCrossover,
    /** CMF extreme zone signal: "ACCUMULATION" (CMF > 0.40), "DISTRIBUTION" (CMF < -0.40), "NEUTRAL". */
    String cmfExtremeSignal
) {
    public record OrderBlockZone(String type, BigDecimal high, BigDecimal low) {}

    /** OB lifecycle event for alert evaluation. */
    public record OrderBlockEvent(String eventType, String obType, BigDecimal high, BigDecimal low) {}

    /** FVG formation/mitigation event for alert evaluation. */
    public record FvgEvent(String bias, BigDecimal top, BigDecimal bottom) {}

    /** Equal-level sweep event for alert evaluation. */
    public record SweepEvent(String type, BigDecimal price) {}
}
