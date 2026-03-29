package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.util.*;

/**
 * Fair Value Gap (FVG) detector — mirrors LuxAlgo Smart Money Concepts algorithm
 * with configurable threshold filtering, dedicated timeframe support, and visual extensions.
 *
 * Bullish FVG : candle[i].low  > candle[i-2].high  (gap above)
 * Bearish FVG : candle[i].high < candle[i-2].low   (gap below)
 *
 * Candles must be sorted oldest → newest (index 0 = oldest).
 * A gap is mitigated once price re-enters it on any subsequent candle.
 *
 * UC-SMC-010: Supports minimum gap size threshold, dedicated detection timeframe,
 * and visual zone extension parameters.
 */
public class FairValueGapDetector {

    private final int maxActive;
    private final BigDecimal minGapSize;  // Minimum gap size to qualify (in price units)
    private final int extensionBars;      // Number of bars to visually extend zone forward

    public FairValueGapDetector(int maxActive) {
        this(maxActive, BigDecimal.ZERO, 0);
    }

    public FairValueGapDetector() {
        this(5, BigDecimal.ZERO, 0);
    }

    /**
     * @param maxActive       maximum number of active FVGs to track
     * @param minGapSize      minimum gap size threshold (BigDecimal.ZERO = no filter)
     * @param extensionBars   visual extension bars forward from gap formation
     */
    public FairValueGapDetector(int maxActive, BigDecimal minGapSize, int extensionBars) {
        this.maxActive       = maxActive;
        this.minGapSize      = minGapSize;
        this.extensionBars   = extensionBars;
    }

    /**
     * One Fair Value Gap with extended metadata.
     *
     * @param bias              "BULLISH" or "BEARISH"
     * @param top               upper price boundary of the gap
     * @param bottom            lower price boundary of the gap
     * @param startBarTime      epoch-seconds of the middle (gap) candle
     * @param extensionEndTime  epoch-seconds of the final extended bar (or startBarTime if no extension)
     */
    public record FairValueGap(
            String     bias,
            BigDecimal top,
            BigDecimal bottom,
            long       startBarTime,
            long       extensionEndTime
    ) {
        /**
         * Legacy constructor for backward compatibility.
         */
        public FairValueGap(String bias, BigDecimal top, BigDecimal bottom, long startBarTime) {
            this(bias, top, bottom, startBarTime, startBarTime);
        }

        /**
         * Gap size in price units.
         */
        public BigDecimal getGapSize() {
            return top.subtract(bottom).abs();
        }
    }

    /**
     * Detect and return up to {@code maxActive} most-recent unmitigated FVGs.
     * Applies threshold filtering and extension parameters.
     */
    public List<FairValueGap> detect(List<Candle> candles) {
        if (candles.size() < 3) return Collections.emptyList();

        List<FairValueGap> all = new ArrayList<>();

        for (int i = 2; i < candles.size(); i++) {
            Candle prev2 = candles.get(i - 2);
            Candle prev1 = candles.get(i - 1); // middle "FVG" candle
            Candle curr  = candles.get(i);

            // Bullish FVG: gap between prev2.high (bottom) and curr.low (top)
            if (curr.getLow().compareTo(prev2.getHigh()) > 0) {
                BigDecimal top = curr.getLow();
                BigDecimal bottom = prev2.getHigh();
                if (passesThreshold(top.subtract(bottom))) {
                    long extensionEnd = computeExtensionEndTime(candles, i);
                    all.add(new FairValueGap("BULLISH", top, bottom, prev1.getTimestamp().getEpochSecond(), extensionEnd));
                }
            }

            // Bearish FVG: gap between curr.high (bottom) and prev2.low (top)
            if (curr.getHigh().compareTo(prev2.getLow()) < 0) {
                BigDecimal top = prev2.getLow();
                BigDecimal bottom = curr.getHigh();
                if (passesThreshold(top.subtract(bottom))) {
                    long extensionEnd = computeExtensionEndTime(candles, i);
                    all.add(new FairValueGap("BEARISH", top, bottom, prev1.getTimestamp().getEpochSecond(), extensionEnd));
                }
            }
        }

        // Keep only gaps that have not been filled yet
        List<FairValueGap> active = new ArrayList<>();
        for (FairValueGap fvg : all) {
            if (!isMitigated(fvg, candles)) {
                active.add(fvg);
            }
        }

        // Return up to maxActive most-recent
        int from = Math.max(0, active.size() - maxActive);
        return Collections.unmodifiableList(new ArrayList<>(active.subList(from, active.size())));
    }

    /**
     * Check if gap passes minimum size threshold (AC1: verify weak FVG filtering).
     */
    private boolean passesThreshold(BigDecimal gapSize) {
        if (minGapSize == null || minGapSize.signum() <= 0) {
            return true; // No threshold applied
        }
        return gapSize.compareTo(minGapSize) >= 0;
    }

    /**
     * Compute visual extension end time: adds extensionBars bars forward from gap formation
     * (AC2: verify visual zone extension calculation).
     */
    private long computeExtensionEndTime(List<Candle> candles, int gapCandleIndex) {
        if (extensionBars <= 0 || gapCandleIndex + extensionBars >= candles.size()) {
            return candles.get(gapCandleIndex).getTimestamp().getEpochSecond();
        }
        return candles.get(gapCandleIndex + extensionBars).getTimestamp().getEpochSecond();
    }

    /** A gap is mitigated when price re-enters it on any candle after its formation. */
    private boolean isMitigated(FairValueGap fvg, List<Candle> candles) {
        boolean past = false;
        for (Candle c : candles) {
            long t = c.getTimestamp().getEpochSecond();
            if (!past) {
                if (t > fvg.startBarTime()) past = true;
                else continue;
            }
            if ("BULLISH".equals(fvg.bias()) && c.getLow().compareTo(fvg.bottom())  < 0) return true;
            if ("BEARISH".equals(fvg.bias()) && c.getHigh().compareTo(fvg.top())    > 0) return true;
        }
        return false;
    }
}
