package com.riskdesk.domain.engine.indicators;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Detects the current market regime based on EMA alignment and Bollinger Band width.
 *
 * <ul>
 *   <li>TRENDING_UP: EMA9 > EMA50 > EMA200 and BB expanding</li>
 *   <li>TRENDING_DOWN: EMA9 < EMA50 < EMA200 and BB expanding</li>
 *   <li>RANGING: EMAs close together and BB contracting</li>
 *   <li>CHOPPY: EMAs crossed without clear direction</li>
 * </ul>
 */
public class MarketRegimeDetector {

    public static final String TRENDING_UP = "TRENDING_UP";
    public static final String TRENDING_DOWN = "TRENDING_DOWN";
    public static final String RANGING = "RANGING";
    public static final String CHOPPY = "CHOPPY";

    /** If |ema9 - ema50| / ema50 < this threshold, EMAs are considered "close". */
    private static final BigDecimal EMA_PROXIMITY_THRESHOLD = new BigDecimal("0.002"); // 0.2%

    /**
     * Detect the current market regime.
     *
     * @param ema9   EMA 9 value (fast)
     * @param ema50  EMA 50 value (medium)
     * @param ema200 EMA 200 value (slow)
     * @param bbExpanding true if Bollinger Band trend is expanding
     * @return the detected regime string
     */
    public String detect(BigDecimal ema9, BigDecimal ema50, BigDecimal ema200, boolean bbExpanding) {
        if (ema9 == null || ema50 == null || ema200 == null) {
            return CHOPPY;
        }

        boolean bullishAlignment = ema9.compareTo(ema50) > 0 && ema50.compareTo(ema200) > 0;
        boolean bearishAlignment = ema9.compareTo(ema50) < 0 && ema50.compareTo(ema200) < 0;
        boolean emasClose = isClose(ema9, ema50);

        if (bullishAlignment && bbExpanding) {
            return TRENDING_UP;
        }
        if (bearishAlignment && bbExpanding) {
            return TRENDING_DOWN;
        }
        if (emasClose && !bbExpanding) {
            return RANGING;
        }
        return CHOPPY;
    }

    /**
     * Count how many consecutive candles (from most recent) share the same regime.
     * Useful for "time in regime" computation.
     *
     * @param ema9Values   list of EMA9 values, oldest first
     * @param ema50Values  list of EMA50 values, oldest first
     * @param ema200Values list of EMA200 values, oldest first
     * @param bbExpandingValues list of BB expanding booleans, oldest first
     * @return number of consecutive candles from the end with the same regime
     */
    public int durationCandles(List<BigDecimal> ema9Values,
                               List<BigDecimal> ema50Values,
                               List<BigDecimal> ema200Values,
                               List<Boolean> bbExpandingValues) {
        if (ema9Values == null || ema9Values.isEmpty()) return 0;

        int size = Math.min(Math.min(ema9Values.size(), ema50Values.size()),
                           Math.min(ema200Values.size(), bbExpandingValues.size()));
        if (size == 0) return 0;

        String currentRegime = detect(
            ema9Values.get(size - 1), ema50Values.get(size - 1),
            ema200Values.get(size - 1), bbExpandingValues.get(size - 1));

        int count = 1;
        for (int i = size - 2; i >= 0; i--) {
            String regime = detect(ema9Values.get(i), ema50Values.get(i),
                                   ema200Values.get(i), bbExpandingValues.get(i));
            if (!regime.equals(currentRegime)) break;
            count++;
        }
        return count;
    }

    /**
     * Check if two timeframes are aligned (same directional regime).
     */
    public boolean htfAligned(String focusRegime, String htfRegime) {
        if (focusRegime == null || htfRegime == null) return false;
        // Both trending in the same direction = aligned
        if (TRENDING_UP.equals(focusRegime) && TRENDING_UP.equals(htfRegime)) return true;
        if (TRENDING_DOWN.equals(focusRegime) && TRENDING_DOWN.equals(htfRegime)) return true;
        return false;
    }

    private boolean isClose(BigDecimal a, BigDecimal b) {
        if (b.compareTo(BigDecimal.ZERO) == 0) return true;
        BigDecimal diff = a.subtract(b).abs();
        BigDecimal ratio = diff.divide(b.abs(), 6, RoundingMode.HALF_UP);
        return ratio.compareTo(EMA_PROXIMITY_THRESHOLD) < 0;
    }
}
