package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.orderflow.model.BreakEnrichment;
import com.riskdesk.domain.orderflow.model.FvgEnrichment;
import com.riskdesk.domain.orderflow.model.LiquidityEnrichment;
import com.riskdesk.domain.orderflow.model.OrderBlockEnrichment;

/**
 * Pure domain service that scores SMC zones with order flow data (Phase 5a).
 * <p>
 * Takes raw SMC zone data (order blocks, FVGs, structure breaks, liquidity pools)
 * combined with order flow metrics (delta, volume, absorption, depth) and produces
 * enrichment records with 0-100 quality/confidence scores.
 * <p>
 * All methods are pure functions — no state, no side effects. No Spring, no I/O.
 */
public final class SmcOrderFlowEnricher {

    private static final double MAX_SCORE = 100.0;
    private static final double ABSORPTION_SCORE_THRESHOLD = 2.0;
    private static final double DEFENDED_ATR_RATIO = 0.3;
    private static final double VOLUME_SPIKE_CONFIRMED_THRESHOLD = 2.0;

    /**
     * Enrich an Order Block with order flow data.
     * <p>
     * Formation score is derived from the delta/volume ratio during the OB-creating impulse.
     * Live score upgrades the formation score when real-time absorption or depth data is available.
     *
     * @param formationDelta   net delta during the impulse that created the OB
     * @param formationVolume  volume during the impulse that created the OB
     * @param absorptionScore  absorption score if detected in this zone (null if none)
     * @param absorptionSide   "BULLISH_ABSORPTION" or "BEARISH_ABSORPTION" (null if none)
     * @param depthSupportRatio bid/ask depth ratio from Level 2 at the zone (null if unavailable)
     * @param priceMoveTicks   price movement in ticks during absorption window
     * @param atr              current ATR in ticks
     * @return enrichment record with formation and live scores
     */
    public OrderBlockEnrichment enrichOrderBlock(
            double formationDelta,
            double formationVolume,
            Double absorptionScore,
            String absorptionSide,
            Double depthSupportRatio,
            double priceMoveTicks,
            double atr) {

        double safeVolume = Math.max(formationVolume, 1.0);
        double formationDeltaRatio = Math.abs(formationDelta) / safeVolume;
        double obFormationScore = Math.min(MAX_SCORE, formationDeltaRatio * 100.0 * 1.5);

        // Defended = absorption detected AND price stayed stable within the zone
        boolean defended = absorptionScore != null
                && absorptionScore > ABSORPTION_SCORE_THRESHOLD
                && priceMoveTicks < atr * DEFENDED_ATR_RATIO;

        // Live score: upgrade formation score with real-time absorption data
        double obLiveScore;
        if (absorptionScore == null) {
            obLiveScore = obFormationScore;
        } else {
            double absorptionComponent = Math.min(absorptionScore * 20.0, 60.0);
            obLiveScore = (obFormationScore * 0.4) + absorptionComponent;
        }

        return new OrderBlockEnrichment(
                formationDelta,
                formationVolume,
                formationDeltaRatio,
                obFormationScore,
                absorptionScore,
                absorptionSide,
                depthSupportRatio,
                defended,
                obLiveScore
        );
    }

    /**
     * Enrich a Fair Value Gap with order flow data.
     * <p>
     * An FVG formed with strong directional delta = real institutional imbalance (high quality).
     * Weak delta during formation = low-liquidity gap, likely to fill quickly.
     *
     * @param gapDelta  net delta during the 3-candle gap formation
     * @param gapVolume total volume during the 3-candle gap formation
     * @return enrichment record with imbalance intensity and quality score
     */
    public FvgEnrichment enrichFvg(double gapDelta, double gapVolume) {
        double safeVolume = Math.max(gapVolume, 1.0);
        double imbalanceIntensity = Math.abs(gapDelta) / safeVolume;
        double fvgQualityScore = Math.min(MAX_SCORE, imbalanceIntensity * 120.0);

        return new FvgEnrichment(
                gapDelta,
                gapVolume,
                imbalanceIntensity,
                fvgQualityScore
        );
    }

    /**
     * Enrich a BOS/CHoCH structure break with order flow data.
     * <p>
     * A break is confirmed when it happens on elevated volume with delta aligned to the break
     * direction. Weak delta or opposing delta = potential fakeout.
     *
     * @param breakDelta  net delta on the break candle
     * @param breakVolume volume of the break candle
     * @param avgVolume   average volume of the preceding candles (e.g., 20-candle lookback)
     * @param isLongBreak true if this is a bullish break (BOS up or CHoCH up)
     * @return enrichment record with volume spike, confirmation, and confidence score
     */
    public BreakEnrichment enrichBreak(
            double breakDelta,
            double breakVolume,
            double avgVolume,
            boolean isLongBreak) {

        double safeAvgVolume = Math.max(avgVolume, 1.0);
        double volumeSpike = breakVolume / safeAvgVolume;

        // Delta aligned = positive delta on bullish break, negative delta on bearish break
        boolean deltaAligned = (isLongBreak && breakDelta > 0) || (!isLongBreak && breakDelta < 0);

        boolean confirmed = volumeSpike > VOLUME_SPIKE_CONFIRMED_THRESHOLD && deltaAligned;

        double breakConfidenceScore = Math.min(MAX_SCORE,
                (volumeSpike * 20.0) + (deltaAligned ? 40.0 : 0.0));

        return new BreakEnrichment(
                breakDelta,
                breakVolume,
                avgVolume,
                volumeSpike,
                confirmed,
                breakConfidenceScore
        );
    }

    /**
     * Enrich an Equal Levels liquidity pool with Level 2 depth data.
     * <p>
     * Confirms whether actual resting orders sit at equal highs/lows levels. A large depth
     * ratio means the level is real liquidity, not just a chart pattern.
     *
     * @param ordersVisible true if the order book shows visible orders at this level
     * @param sizeAtLevel   total size of visible orders at this level
     * @param avgLevelSize  average order size at a single book level
     * @return enrichment record with depth ratio and confirmation score
     */
    public LiquidityEnrichment enrichLiquidity(
            boolean ordersVisible,
            long sizeAtLevel,
            double avgLevelSize) {

        double safeAvgSize = Math.max(avgLevelSize, 1.0);
        double depthRatioAtLevel = sizeAtLevel / safeAvgSize;

        double liquidityConfirmScore = ordersVisible
                ? Math.min(MAX_SCORE, depthRatioAtLevel * 50.0)
                : 0.0;

        return new LiquidityEnrichment(
                ordersVisible,
                sizeAtLevel,
                depthRatioAtLevel,
                liquidityConfirmScore
        );
    }
}
