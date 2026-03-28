package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.model.Candle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects Equal Highs (EQH) and Equal Lows (EQL) — horizontal liquidity
 * zones where consecutive swing pivots form at approximately the same price.
 * <p>
 * Mirrors LuxAlgo {@code eq_threshold} logic:
 * two consecutive swing highs (or lows) are "equal" when their price
 * difference is within {@code threshold * averagePrice / 100}.
 * <p>
 * Pivot detection uses right-side-only confirmation identical to
 * {@link SmcStructureEngine}: a bar at {@code [lookback]} positions back
 * is confirmed as a swing HIGH when its high exceeds the highest high of
 * the subsequent {@code lookback} bars. LOW pivots use the symmetric rule.
 */
public class EqualLevelDetector {

    public enum EqualType { EQH, EQL }

    /**
     * An equal-level zone: two consecutive swing pivots at approximately
     * the same price, representing a liquidity pool.
     *
     * @param type         EQH or EQL
     * @param price        average of the two pivot prices
     * @param firstPrice   exact price of the first pivot
     * @param secondPrice  exact price of the second pivot
     * @param firstBar     bar index of the first pivot
     * @param secondBar    bar index of the second pivot
     * @param firstTime    timestamp of the first pivot
     * @param secondTime   timestamp of the second pivot
     */
    public record EqualLevel(
            EqualType type,
            double price,
            double firstPrice,
            double secondPrice,
            int firstBar,
            int secondBar,
            Instant firstTime,
            Instant secondTime) {}

    private final int lookback;
    private final double threshold;  // percentage (e.g. 0.1 = 0.1%)

    /**
     * @param lookback  right-side confirmation bars for pivot detection
     * @param threshold percentage threshold for "equal" (LuxAlgo default: 0.1)
     */
    public EqualLevelDetector(int lookback, double threshold) {
        this.lookback = lookback;
        this.threshold = threshold;
    }

    /** LuxAlgo defaults: lookback=5, threshold=0.1%. */
    public EqualLevelDetector() {
        this(5, 0.1);
    }

    /**
     * Detect all EQH/EQL zones in the given candle series.
     *
     * @param candles chronologically ordered closed candles
     * @return list of detected equal-level zones
     */
    public List<EqualLevel> detect(List<Candle> candles) {
        int n = candles.size();
        if (n < lookback + 1) return List.of();

        // 1. Collect all confirmed swing pivots (right-side only)
        List<int[]> swingHighs = new ArrayList<>();  // [barIndex]
        List<int[]> swingLows = new ArrayList<>();

        double[] highs = new double[n];
        double[] lows = new double[n];
        Instant[] times = new Instant[n];
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            highs[i] = c.getHigh().doubleValue();
            lows[i] = c.getLow().doubleValue();
            times[i] = c.getTimestamp();
        }

        for (int cand = 0; cand + lookback < n; cand++) {
            double candH = highs[cand];
            double candL = lows[cand];

            // Right-side confirmation window: bars [cand+1 .. cand+lookback]
            double maxH = Double.NEGATIVE_INFINITY;
            double minL = Double.POSITIVE_INFINITY;
            for (int j = 1; j <= lookback; j++) {
                int idx = cand + j;
                if (highs[idx] > maxH) maxH = highs[idx];
                if (lows[idx] < minL) minL = lows[idx];
            }

            if (candH > maxH) swingHighs.add(new int[]{cand});
            if (candL < minL) swingLows.add(new int[]{cand});
        }

        // 2. Compare consecutive same-type pivots for equality
        List<EqualLevel> results = new ArrayList<>();

        for (int i = 1; i < swingHighs.size(); i++) {
            int bar1 = swingHighs.get(i - 1)[0];
            int bar2 = swingHighs.get(i)[0];
            double p1 = highs[bar1];
            double p2 = highs[bar2];
            if (isEqual(p1, p2)) {
                double avg = (p1 + p2) / 2;
                results.add(new EqualLevel(EqualType.EQH, avg, p1, p2,
                        bar1, bar2, times[bar1], times[bar2]));
            }
        }

        for (int i = 1; i < swingLows.size(); i++) {
            int bar1 = swingLows.get(i - 1)[0];
            int bar2 = swingLows.get(i)[0];
            double p1 = lows[bar1];
            double p2 = lows[bar2];
            if (isEqual(p1, p2)) {
                double avg = (p1 + p2) / 2;
                results.add(new EqualLevel(EqualType.EQL, avg, p1, p2,
                        bar1, bar2, times[bar1], times[bar2]));
            }
        }

        return results;
    }

    /**
     * Two prices are "equal" when their absolute difference is within
     * {@code threshold%} of their average. Matches LuxAlgo eq_threshold.
     */
    private boolean isEqual(double p1, double p2) {
        double avg = (p1 + p2) / 2;
        if (avg == 0) return p1 == p2;
        return Math.abs(p1 - p2) / avg * 100 <= threshold;
    }
}
