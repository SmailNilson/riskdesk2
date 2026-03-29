package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
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
    private enum ThresholdMode { PERCENTAGE, ABSOLUTE_PRICE }

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

    /**
     * Aggregated liquidity pool built from one or more nearby equal pivots.
     * Pools remain active until price trades through them ("swept").
     */
    public record LiquidityPool(
            EqualType type,
            double price,
            double upperBound,
            double lowerBound,
            int firstBar,
            int lastBar,
            Instant firstTime,
            Instant lastTime,
            int touchCount,
            boolean swept,
            Instant sweptTime) {}

    private final int lookback;
    private final ThresholdMode thresholdMode;
    private final double threshold;

    /**
     * @param lookback  right-side confirmation bars for pivot detection
     * @param threshold percentage threshold for "equal" (LuxAlgo default: 0.1)
     */
    public EqualLevelDetector(int lookback, double threshold) {
        this.lookback = lookback;
        this.thresholdMode = ThresholdMode.PERCENTAGE;
        this.threshold = threshold;
    }

    /**
     * Tick-normalized threshold for futures instruments.
     *
     * @param lookback        right-side confirmation bars for pivot detection
     * @param tickSize        instrument minimum tick size
     * @param toleranceTicks  maximum distance between pivots inside one pool
     */
    public EqualLevelDetector(int lookback, BigDecimal tickSize, int toleranceTicks) {
        this.lookback = lookback;
        this.thresholdMode = ThresholdMode.ABSOLUTE_PRICE;
        this.threshold = tickSize.multiply(BigDecimal.valueOf(toleranceTicks)).doubleValue();
    }

    /** LuxAlgo defaults: lookback=5, threshold=0.1%. */
    public EqualLevelDetector() {
        this(5, 0.1);
    }

    public static EqualLevelDetector tickNormalized(int lookback, BigDecimal tickSize, int toleranceTicks) {
        return new EqualLevelDetector(lookback, tickSize, toleranceTicks);
    }

    /**
     * Detect all EQH/EQL zones in the given candle series.
     *
     * @param candles chronologically ordered closed candles
     * @return list of detected equal-level zones
     */
    public List<EqualLevel> detect(List<Candle> candles) {
        return detectPools(candles).stream()
                .map(pool -> new EqualLevel(
                        pool.type(),
                        pool.price(),
                        pool.upperBound(),
                        pool.lowerBound(),
                        pool.firstBar(),
                        pool.lastBar(),
                        pool.firstTime(),
                        pool.lastTime()))
                .toList();
    }

    /**
     * Detect and aggregate active liquidity pools from equal highs / lows.
     */
    public List<LiquidityPool> detectPools(List<Candle> candles) {
        int n = candles.size();
        if (n < lookback + 1) return List.of();

        // 1. Collect all confirmed swing pivots (right-side only)
        List<PivotCandidate> swingHighs = new ArrayList<>();
        List<PivotCandidate> swingLows = new ArrayList<>();

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

            if (candH > maxH) swingHighs.add(new PivotCandidate(cand, candH, times[cand]));
            if (candL < minL) swingLows.add(new PivotCandidate(cand, candL, times[cand]));
        }

        List<LiquidityPool> results = new ArrayList<>();
        clusterPivots(swingHighs, EqualType.EQH, candles, results);
        clusterPivots(swingLows, EqualType.EQL, candles, results);
        return results.stream()
                .filter(pool -> !pool.swept())
                .toList();
    }

    /**
     * Two prices are "equal" when their absolute difference is within
     * the configured tolerance.
     */
    private boolean isEqual(double p1, double p2) {
        if (thresholdMode == ThresholdMode.ABSOLUTE_PRICE) {
            return Math.abs(p1 - p2) <= threshold;
        }
        double avg = (p1 + p2) / 2;
        if (avg == 0) return p1 == p2;
        return Math.abs(p1 - p2) / avg * 100 <= threshold;
    }

    private void clusterPivots(
            List<PivotCandidate> pivots,
            EqualType type,
            List<Candle> candles,
            List<LiquidityPool> out
    ) {
        if (pivots.size() < 2) return;

        List<PivotCluster> clusters = new ArrayList<>();
        PivotCluster current = new PivotCluster(pivots.get(0));
        for (int i = 1; i < pivots.size(); i++) {
            PivotCandidate pivot = pivots.get(i);
            if (isEqual(current.referencePrice(), pivot.price())) {
                current.add(pivot);
                continue;
            }
            clusters.add(current);
            current = new PivotCluster(pivot);
        }
        clusters.add(current);

        List<PivotCluster> merged = new ArrayList<>();
        for (PivotCluster cluster : clusters) {
            if (!merged.isEmpty() && isEqual(merged.get(merged.size() - 1).referencePrice(), cluster.referencePrice())) {
                merged.get(merged.size() - 1).merge(cluster);
            } else {
                merged.add(cluster);
            }
        }

        for (PivotCluster cluster : merged) {
            emitCluster(cluster, type, candles, out);
        }
    }

    private void emitCluster(
            PivotCluster cluster,
            EqualType type,
            List<Candle> candles,
            List<LiquidityPool> out
    ) {
        if (cluster.touchCount < 2) return;
        Instant sweptTime = detectSweep(type, cluster, candles);
        out.add(new LiquidityPool(
                type,
                cluster.referencePrice(),
                cluster.maxPrice,
                cluster.minPrice,
                cluster.firstBar,
                cluster.lastBar,
                cluster.firstTime,
                cluster.lastTime,
                cluster.touchCount,
                sweptTime != null,
                sweptTime
        ));
    }

    private Instant detectSweep(EqualType type, PivotCluster cluster, List<Candle> candles) {
        for (int i = cluster.lastBar + 1; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (type == EqualType.EQH && candle.getHigh().doubleValue() > cluster.maxPrice) {
                return candle.getTimestamp();
            }
            if (type == EqualType.EQL && candle.getLow().doubleValue() < cluster.minPrice) {
                return candle.getTimestamp();
            }
        }
        return null;
    }

    private record PivotCandidate(int barIndex, double price, Instant time) {}

    private static final class PivotCluster {
        private int firstBar;
        private int lastBar;
        private Instant firstTime;
        private Instant lastTime;
        private double minPrice;
        private double maxPrice;
        private double sumPrice;
        private int touchCount;

        private PivotCluster(PivotCandidate pivot) {
            this.firstBar = pivot.barIndex();
            this.lastBar = pivot.barIndex();
            this.firstTime = pivot.time();
            this.lastTime = pivot.time();
            this.minPrice = pivot.price();
            this.maxPrice = pivot.price();
            this.sumPrice = pivot.price();
            this.touchCount = 1;
        }

        private void add(PivotCandidate pivot) {
            this.lastBar = pivot.barIndex();
            this.lastTime = pivot.time();
            this.minPrice = Math.min(this.minPrice, pivot.price());
            this.maxPrice = Math.max(this.maxPrice, pivot.price());
            this.sumPrice += pivot.price();
            this.touchCount++;
        }

        private void merge(PivotCluster other) {
            this.lastBar = other.lastBar;
            this.lastTime = other.lastTime;
            this.minPrice = Math.min(this.minPrice, other.minPrice);
            this.maxPrice = Math.max(this.maxPrice, other.maxPrice);
            this.sumPrice += other.sumPrice;
            this.touchCount += other.touchCount;
        }

        private double referencePrice() {
            return sumPrice / touchCount;
        }
    }
}
