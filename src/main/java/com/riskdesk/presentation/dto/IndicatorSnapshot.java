package com.riskdesk.presentation.dto;

import java.math.BigDecimal;
import java.util.List;

public record IndicatorSnapshot(
        String instrument,
        String timeframe,
        // EMAs
        BigDecimal ema9,
        BigDecimal ema50,
        BigDecimal ema200,
        String emaCrossover,
        // RSI
        BigDecimal rsi,
        String rsiSignal,
        // MACD
        BigDecimal macdLine,
        BigDecimal macdSignal,
        BigDecimal macdHistogram,
        String macdCrossover,
        // Supertrend
        BigDecimal supertrendValue,
        boolean supertrendBullish,
        // VWAP
        BigDecimal vwap,
        BigDecimal vwapUpperBand,
        BigDecimal vwapLowerBand,
        // Chaikin
        BigDecimal chaikinOscillator,
        BigDecimal cmf,
        // Bollinger Bands (20, SMA, 2σ)
        BigDecimal bbMiddle,
        BigDecimal bbUpper,
        BigDecimal bbLower,
        BigDecimal bbWidth,
        BigDecimal bbPct,
        // BBTrend (14/30, factor 2)
        BigDecimal bbTrendValue,
        boolean bbTrendExpanding,
        String bbTrendSignal,
        // Delta Flow Profile (buy vs sell volume delta)
        BigDecimal deltaFlow,
        BigDecimal cumulativeDelta,
        BigDecimal buyRatio,
        String deltaFlowBias,
        // WaveTrend Oscillator (n1=10, n2=21, signal=4)
        BigDecimal wtWt1,
        BigDecimal wtWt2,
        BigDecimal wtDiff,
        String wtCrossover,
        String wtSignal,
        // SMC — Market Structure
        String marketStructureTrend,
        BigDecimal strongHigh,
        BigDecimal strongLow,
        BigDecimal weakHigh,
        BigDecimal weakLow,
        String lastBreakType,
        // SMC — timestamps for chart rendering (epoch seconds, nullable)
        Long strongHighTime,
        Long strongLowTime,
        Long weakHighTime,
        Long weakLowTime,
        // SMC — Order Blocks
        List<OrderBlockView> activeOrderBlocks,
        // SMC — Fair Value Gaps (LuxAlgo algorithm)
        List<FairValueGapView> activeFairValueGaps,
        // SMC — recent BOS / CHoCH breaks with bar timestamps
        List<StructureBreakView> recentBreaks
) {
    /** Displayed order block zone. startTime is epoch-seconds of the formation candle. */
    public record OrderBlockView(String type, BigDecimal high, BigDecimal low, BigDecimal mid, long startTime) {}

    /** One active Fair Value Gap. startTime is epoch-seconds of the gap's middle candle. */
    public record FairValueGapView(String bias, BigDecimal top, BigDecimal bottom, long startTime) {}

    /** One BOS / CHoCH event for chart marker rendering. barTime is epoch-seconds. */
    public record StructureBreakView(String type, String trend, BigDecimal level, long barTime) {}
}
