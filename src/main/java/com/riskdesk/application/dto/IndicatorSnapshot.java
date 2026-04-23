package com.riskdesk.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Snapshot of all indicator values for one (instrument, timeframe) pair at
 * one point in time.
 *
 * <h2>Pivot staleness — {@code internalHigh/Low}, {@code swingHigh/Low},
 * {@code strongHigh/Low}, {@code weakHigh/Low}</h2>
 *
 * Every pivot-derived level on this record originates in
 * {@link com.riskdesk.domain.engine.smc.SmcStructureEngine.Pivot} and therefore
 * carries an inherent confirmation lag equal to the corresponding lookback:
 * <ul>
 *   <li>{@code internal*} — default lookback 5 bars (≈50 min on 10m, ≈5 h on 1h)</li>
 *   <li>{@code swing*}, {@code strong*}, {@code weak*} — default lookback 50 bars
 *       (≈8 h 20 min on 10m, ≈2 days on 1h)</li>
 * </ul>
 * Consequently the {@code *Time} fields here refer to the candidate bar's close
 * time, not "when the pivot was detected". Any UI element that labels these as
 * "latest" / "current" should qualify them as structural anchors.
 */
public record IndicatorSnapshot(
    String instrument,
    String timeframe,
    BigDecimal ema9,
    BigDecimal ema50,
    BigDecimal ema200,
    String emaCrossover,
    BigDecimal rsi,
    String rsiSignal,
    BigDecimal macdLine,
    BigDecimal macdSignal,
    BigDecimal macdHistogram,
    String macdCrossover,
    BigDecimal supertrendValue,
    boolean supertrendBullish,
    BigDecimal vwap,
    BigDecimal vwapUpperBand,
    BigDecimal vwapLowerBand,
    BigDecimal chaikinOscillator,
    BigDecimal cmf,
    BigDecimal bbMiddle,
    BigDecimal bbUpper,
    BigDecimal bbLower,
    BigDecimal bbWidth,
    BigDecimal bbPct,
    BigDecimal bbTrendValue,
    boolean bbTrendExpanding,
    String bbTrendSignal,
    BigDecimal deltaFlow,
    BigDecimal cumulativeDelta,
    BigDecimal buyRatio,
    String deltaFlowBias,
    BigDecimal wtWt1,
    BigDecimal wtWt2,
    BigDecimal wtDiff,
    String wtCrossover,
    String wtSignal,
    // ── Stochastic Oscillator ───────────────────────────────────────────
    BigDecimal stochK,
    BigDecimal stochD,
    String stochSignal,
    String stochCrossover,
    // ── SMC: Internal structure ────────────────────────────────────────
    String internalBias,
    BigDecimal internalHigh,
    BigDecimal internalLow,
    Long internalHighTime,
    Long internalLowTime,
    String lastInternalBreakType,

    // ── SMC: Swing structure ─────────────────────────────────────────
    String swingBias,
    BigDecimal swingHigh,
    BigDecimal swingLow,
    Long swingHighTime,
    Long swingLowTime,
    String lastSwingBreakType,

    // ── SMC: UC-SMC-008 confluence filter state ──────────────────────
    boolean internalConfluenceFilterEnabled,

    // ── SMC: Multi-resolution bias (swing lookbacks 50/25/9, internal 5, micro 1)
    MultiResolutionBias multiResolutionBias,

    // ── SMC: Legacy / derived (kept for frontend backward compat) ────
    String marketStructureTrend,
    BigDecimal strongHigh,
    BigDecimal strongLow,
    BigDecimal weakHigh,
    BigDecimal weakLow,
    String lastBreakType,
    Long strongHighTime,
    Long strongLowTime,
    Long weakHighTime,
    Long weakLowTime,

    // ── SMC: Liquidity (EQH / EQL) ─────────────────────────────────
    List<EqualLevelView> equalHighs,
    List<EqualLevelView> equalLows,

    // ── SMC: Premium / Discount / Equilibrium (UC-SMC-004) ─────────
    BigDecimal premiumZoneTop,
    BigDecimal equilibriumLevel,
    BigDecimal discountZoneBottom,
    String currentZone,

    // ── SMC: Zones ───────────────────────────────────────────────────
    List<OrderBlockView> activeOrderBlocks,
    List<OrderBlockView> breakerOrderBlocks,
    List<OrderBlockEventView> recentOrderBlockEvents,
    List<FairValueGapView> activeFairValueGaps,
    List<StructureBreakView> recentBreaks,

    // ── UC-SMC-005: Multi-timeframe levels (Daily / Weekly / Monthly) ───
    MtfLevelsView mtfLevels,

    // ── Session PD Array (intraday range-based) ─────────────────────────
    BigDecimal sessionHigh,
    BigDecimal sessionLow,
    BigDecimal sessionEquilibrium,
    String sessionPdZone,

    // ── UC-OF-012: Volume Profile ─────────────────────────────────────
    Double pocPrice,
    Double valueAreaHigh,
    Double valueAreaLow,

    // ── UC-OF-013: Session CME Context ────────────────────────────────
    String sessionPhase,

    /** Timestamp of the last candle used to compute this snapshot (Rule 4: candle close guard). */
    Instant lastCandleTimestamp,

    /** Close price of the last candle — used by behaviour alert rules for proximity evaluation. */
    BigDecimal lastPrice
) {
    public record OrderBlockView(
        String type,
        String status,
        BigDecimal high,
        BigDecimal low,
        BigDecimal mid,
        long startTime,
        String originalType,
        Long breakerTime,
        // ── Order Flow enrichment (Phase 5a, nullable) ──────────────
        Double formationDelta,
        Double obFormationScore,
        Double obLiveScore,
        Boolean defended,
        Double absorptionScore
    ) {
        /** Backward-compatible constructor — OF fields default to null. */
        public OrderBlockView(String type, String status, BigDecimal high, BigDecimal low,
                              BigDecimal mid, long startTime, String originalType, Long breakerTime) {
            this(type, status, high, low, mid, startTime, originalType, breakerTime,
                 null, null, null, null, null);
        }
    }

    public record FairValueGapView(
        String bias, BigDecimal top, BigDecimal bottom, long startTime, long extensionEndTime,
        // ── Order Flow enrichment (Phase 5a, nullable) ──────────────
        Double gapDelta,
        Double fvgQualityScore
    ) {
        /** Backward-compatible constructor — OF fields default to null. */
        public FairValueGapView(String bias, BigDecimal top, BigDecimal bottom, long startTime, long extensionEndTime) {
            this(bias, top, bottom, startTime, extensionEndTime, null, null);
        }
    }

    public record StructureBreakView(
        String type, String trend, BigDecimal level, long barTime, String structureLevel,
        // ── Order Flow enrichment (Phase 5a, nullable) ──────────────
        Double breakDelta,
        Double volumeSpike,
        Boolean confirmed,
        Double breakConfidenceScore
    ) {
        /** Backward-compatible constructor — OF fields default to null. */
        public StructureBreakView(String type, String trend, BigDecimal level, long barTime, String structureLevel) {
            this(type, trend, level, barTime, structureLevel, null, null, null, null);
        }
    }

    public record EqualLevelView(
        String type, BigDecimal price, long firstBarTime, long lastBarTime, int touchCount,
        // ── Order Flow enrichment (Phase 5a, nullable) ──────────────
        Boolean ordersVisible,
        Long depthSizeAtLevel,
        Double liquidityConfirmScore
    ) {
        /** Backward-compatible constructor — OF fields default to null. */
        public EqualLevelView(String type, BigDecimal price, long firstBarTime, long lastBarTime, int touchCount) {
            this(type, price, firstBarTime, lastBarTime, touchCount, null, null, null);
        }
    }

    /** UC-SMC-009: OB lifecycle event (MITIGATION or INVALIDATION). */
    public record OrderBlockEventView(String eventType, String obType, BigDecimal high, BigDecimal low, long eventTime) {}

    /** UC-SMC-005: OHLC levels for a single higher timeframe candle. */
    public record MtfLevelView(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}

    /** UC-SMC-005: Multi-timeframe OHLC levels (daily, weekly, monthly). Null means no data for that timeframe. */
    public record MtfLevelsView(MtfLevelView daily, MtfLevelView weekly, MtfLevelView monthly) {}

    /** Multi-resolution market structure bias — 5 lookback scales for richer Gemini context. */
    public record MultiResolutionBias(String swing50, String swing25, String swing9, String internal5, String micro1) {}
}
