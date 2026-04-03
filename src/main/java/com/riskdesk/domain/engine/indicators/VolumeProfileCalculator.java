package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Calculates a session Volume Profile from candles:
 * - POC (Point of Control): price level with highest volume
 * - Value Area (70% of total volume around POC)
 */
public class VolumeProfileCalculator {

    private static final BigDecimal VALUE_AREA_PCT = new BigDecimal("0.70");

    /**
     * Compute a volume profile from a list of candles.
     *
     * @param candles the candles to analyze (typically one session's worth)
     * @param tickSize the instrument's tick size for price bucketing
     * @param bucketMultiplier how many ticks per bucket (e.g., 10 for larger buckets)
     * @return the volume profile result, or null if insufficient data
     */
    public VolumeProfileResult compute(List<Candle> candles, BigDecimal tickSize, int bucketMultiplier) {
        if (candles == null || candles.size() < 5 || tickSize == null || tickSize.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        BigDecimal bucketSize = tickSize.multiply(BigDecimal.valueOf(bucketMultiplier));

        // Build volume-by-price histogram
        TreeMap<BigDecimal, Long> histogram = new TreeMap<>();
        long totalVolume = 0;

        for (Candle candle : candles) {
            if (candle.getVolume() <= 0) continue;

            // Distribute candle volume across its range using typical price
            BigDecimal typical = candle.getHigh().add(candle.getLow()).add(candle.getClose())
                .divide(BigDecimal.valueOf(3), 10, RoundingMode.HALF_UP);
            BigDecimal bucket = typical.divide(bucketSize, 0, RoundingMode.HALF_UP).multiply(bucketSize);

            histogram.merge(bucket, candle.getVolume(), Long::sum);
            totalVolume += candle.getVolume();
        }

        if (histogram.isEmpty() || totalVolume == 0) {
            return null;
        }

        // Find POC (highest volume bucket)
        BigDecimal poc = null;
        long pocVolume = 0;
        for (var entry : histogram.entrySet()) {
            if (entry.getValue() > pocVolume) {
                pocVolume = entry.getValue();
                poc = entry.getKey();
            }
        }

        // Compute Value Area (70% of volume around POC)
        long vaTarget = (long) (totalVolume * VALUE_AREA_PCT.doubleValue());
        long vaVolume = pocVolume;
        BigDecimal vaHigh = poc;
        BigDecimal vaLow = poc;

        // Expand outward from POC
        NavigableMap<BigDecimal, Long> above = histogram.tailMap(poc, false);
        NavigableMap<BigDecimal, Long> below = histogram.headMap(poc, false).descendingMap();
        Iterator<Map.Entry<BigDecimal, Long>> aboveIt = above.entrySet().iterator();
        Iterator<Map.Entry<BigDecimal, Long>> belowIt = below.entrySet().iterator();

        while (vaVolume < vaTarget && (aboveIt.hasNext() || belowIt.hasNext())) {
            long aboveVol = aboveIt.hasNext() ? peekValue(above, vaHigh, bucketSize, true) : 0;
            long belowVol = belowIt.hasNext() ? peekValue(below, vaLow, bucketSize, false) : 0;

            if (aboveVol >= belowVol && aboveIt.hasNext()) {
                var entry = aboveIt.next();
                vaHigh = entry.getKey();
                vaVolume += entry.getValue();
            } else if (belowIt.hasNext()) {
                var entry = belowIt.next();
                vaLow = entry.getKey();
                vaVolume += entry.getValue();
            } else if (aboveIt.hasNext()) {
                var entry = aboveIt.next();
                vaHigh = entry.getKey();
                vaVolume += entry.getValue();
            }
        }

        return new VolumeProfileResult(poc, vaHigh, vaLow);
    }

    private long peekValue(NavigableMap<BigDecimal, Long> map, BigDecimal current, BigDecimal step, boolean higher) {
        BigDecimal next = higher ? current.add(step) : current.subtract(step);
        Long vol = map.get(next);
        return vol != null ? vol : 0;
    }

    /**
     * Volume Profile result.
     */
    public record VolumeProfileResult(
        BigDecimal pocPrice,       // Point of Control: highest volume price
        BigDecimal valueAreaHigh,  // Top of the 70% value area
        BigDecimal valueAreaLow    // Bottom of the 70% value area
    ) {}
}
