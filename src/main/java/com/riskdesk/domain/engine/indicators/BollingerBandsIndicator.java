package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Bollinger Bands (period 20, SMA, 2 stddev) + BBTrend (periods 14/30, factor 2).
 *
 * BBTrend measures band width divergence between two BB periods:
 *   – Positive (expanding 14 > 30): trending / directional move
 *   – Negative (contracting 14 < 30): consolidation / squeeze
 */
public class BollingerBandsIndicator {

    private final int period;
    private final double stddevMultiplier;

    // BBTrend periods
    private final int trendFastPeriod;
    private final int trendSlowPeriod;
    private final double trendFactor;

    public BollingerBandsIndicator(int period, double stddevMultiplier,
                                    int trendFastPeriod, int trendSlowPeriod, double trendFactor) {
        this.period = period;
        this.stddevMultiplier = stddevMultiplier;
        this.trendFastPeriod = trendFastPeriod;
        this.trendSlowPeriod = trendSlowPeriod;
        this.trendFactor = trendFactor;
    }

    public BollingerBandsIndicator() {
        this(20, 2.0, 14, 30, 2.0);
    }

    public record BBResult(BigDecimal middle, BigDecimal upper, BigDecimal lower, BigDecimal width, BigDecimal pct) {}

    public record BBTrendResult(BigDecimal value, boolean expanding, String signal) {}

    // -----------------------------------------------------------------------
    // Bollinger Bands
    // -----------------------------------------------------------------------

    public List<BBResult> calculate(List<Candle> candles) {
        if (candles.size() < period) return Collections.emptyList();

        List<BBResult> results = new ArrayList<>();

        for (int i = period - 1; i < candles.size(); i++) {
            // SMA of last `period` closes
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i - period + 1; j <= i; j++) {
                sum = sum.add(candles.get(j).getClose());
            }
            BigDecimal sma = sum.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

            // Population stddev
            BigDecimal sumSqDiff = BigDecimal.ZERO;
            for (int j = i - period + 1; j <= i; j++) {
                BigDecimal diff = candles.get(j).getClose().subtract(sma);
                sumSqDiff = sumSqDiff.add(diff.multiply(diff));
            }
            BigDecimal variance = sumSqDiff.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
            BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

            BigDecimal band = stdDev.multiply(BigDecimal.valueOf(stddevMultiplier));
            BigDecimal upper = sma.add(band).setScale(5, RoundingMode.HALF_UP);
            BigDecimal lower = sma.subtract(band).setScale(5, RoundingMode.HALF_UP);
            BigDecimal width = upper.subtract(lower).setScale(5, RoundingMode.HALF_UP);

            // %B = (close - lower) / (upper - lower)
            BigDecimal close = candles.get(i).getClose();
            BigDecimal pct = width.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.valueOf(0.5) :
                    close.subtract(lower).divide(width, 4, RoundingMode.HALF_UP);

            results.add(new BBResult(sma.setScale(5, RoundingMode.HALF_UP), upper, lower, width, pct));
        }

        return results;
    }

    public BBResult current(List<Candle> candles) {
        List<BBResult> values = calculate(candles);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    // -----------------------------------------------------------------------
    // BBTrend — width ratio between fast and slow BB
    // -----------------------------------------------------------------------

    public List<BBTrendResult> calculateTrend(List<Candle> candles) {
        if (candles.size() < trendSlowPeriod) return Collections.emptyList();

        List<BBTrendResult> results = new ArrayList<>();
        int startIdx = trendSlowPeriod - 1;

        for (int i = startIdx; i < candles.size(); i++) {
            double widthFast = bandWidth(candles, i, trendFastPeriod, trendFactor);
            double widthSlow = bandWidth(candles, i, trendSlowPeriod, trendFactor);

            double trendValue = widthFast - widthSlow;
            boolean expanding = trendValue > 0;
            String signal = expanding ? "TRENDING" : "CONSOLIDATING";

            results.add(new BBTrendResult(
                    BigDecimal.valueOf(trendValue).setScale(5, RoundingMode.HALF_UP),
                    expanding, signal));
        }

        return results;
    }

    public BBTrendResult currentTrend(List<Candle> candles) {
        List<BBTrendResult> values = calculateTrend(candles);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /** Band width for a given lookback ending at index i. */
    private double bandWidth(List<Candle> candles, int endIdx, int lookback, double factor) {
        if (endIdx < lookback - 1) return 0;
        double sum = 0;
        for (int j = endIdx - lookback + 1; j <= endIdx; j++) {
            sum += candles.get(j).getClose().doubleValue();
        }
        double sma = sum / lookback;
        double sumSqDiff = 0;
        for (int j = endIdx - lookback + 1; j <= endIdx; j++) {
            double diff = candles.get(j).getClose().doubleValue() - sma;
            sumSqDiff += diff * diff;
        }
        double stdDev = Math.sqrt(sumSqDiff / lookback);
        return 2 * factor * stdDev;
    }
}
