package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Standalone ATR (Average True Range) calculator using Wilder's smoothing.
 * Extracted from SupertrendIndicator for reuse in trailing stop simulation.
 */
public final class AtrCalculator {

    private AtrCalculator() {
    }

    /**
     * Compute the latest ATR value from a list of candles using Wilder's smoothing.
     *
     * @param candles ordered candles (oldest first)
     * @param period  ATR period (typically 14)
     * @return latest ATR value, or null if insufficient data
     */
    public static BigDecimal compute(List<Candle> candles, int period) {
        if (candles == null || candles.size() <= period) {
            return null;
        }

        // Calculate True Ranges
        BigDecimal[] trueRanges = new BigDecimal[candles.size()];
        trueRanges[0] = candles.get(0).range();

        for (int i = 1; i < candles.size(); i++) {
            BigDecimal high = candles.get(i).getHigh();
            BigDecimal low = candles.get(i).getLow();
            BigDecimal prevClose = candles.get(i - 1).getClose();

            trueRanges[i] = high.subtract(low)
                    .max(high.subtract(prevClose).abs())
                    .max(low.subtract(prevClose).abs());
        }

        // Initial ATR = SMA of first 'period' true ranges
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(trueRanges[i]);
        }
        BigDecimal periodDec = BigDecimal.valueOf(period);
        BigDecimal currentAtr = sum.divide(periodDec, 10, RoundingMode.HALF_UP);

        // Wilder's smoothing for remaining
        for (int i = period; i < trueRanges.length; i++) {
            currentAtr = currentAtr.multiply(periodDec.subtract(BigDecimal.ONE))
                    .add(trueRanges[i])
                    .divide(periodDec, 10, RoundingMode.HALF_UP);
        }

        return currentAtr;
    }
}
