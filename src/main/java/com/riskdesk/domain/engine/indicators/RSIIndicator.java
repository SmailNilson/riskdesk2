package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * RSI (Relative Strength Index) using Wilder's smoothing.
 * Default period 14, custom OB/OS levels matching your setup (33/40/60).
 */
public class RSIIndicator implements TechnicalIndicator<BigDecimal> {

    private final int period;
    private final BigDecimal oversold;
    private final BigDecimal neutral;
    private final BigDecimal overbought;

    public RSIIndicator(int period, double oversold, double neutral, double overbought) {
        this.period = period;
        this.oversold = BigDecimal.valueOf(oversold);
        this.neutral = BigDecimal.valueOf(neutral);
        this.overbought = BigDecimal.valueOf(overbought);
    }

    public RSIIndicator() {
        this(14, 33.0, 40.0, 60.0);
    }

    public List<BigDecimal> calculate(List<Candle> candles) {
        if (candles.size() <= period) return Collections.emptyList();

        List<BigDecimal> results = new ArrayList<>();
        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;

        // Initial average gain/loss
        for (int i = 1; i <= period; i++) {
            BigDecimal change = candles.get(i).getClose().subtract(candles.get(i - 1).getClose());
            if (change.compareTo(BigDecimal.ZERO) > 0) {
                avgGain = avgGain.add(change);
            } else {
                avgLoss = avgLoss.add(change.abs());
            }
        }
        avgGain = avgGain.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

        results.add(computeRSI(avgGain, avgLoss));

        // Wilder's smoothing
        BigDecimal periodDec = BigDecimal.valueOf(period);
        BigDecimal periodMinus1 = BigDecimal.valueOf(period - 1);

        for (int i = period + 1; i < candles.size(); i++) {
            BigDecimal change = candles.get(i).getClose().subtract(candles.get(i - 1).getClose());
            BigDecimal gain = change.compareTo(BigDecimal.ZERO) > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.compareTo(BigDecimal.ZERO) < 0 ? change.abs() : BigDecimal.ZERO;

            avgGain = avgGain.multiply(periodMinus1).add(gain).divide(periodDec, 10, RoundingMode.HALF_UP);
            avgLoss = avgLoss.multiply(periodMinus1).add(loss).divide(periodDec, 10, RoundingMode.HALF_UP);

            results.add(computeRSI(avgGain, avgLoss));
        }

        return results;
    }

    private BigDecimal computeRSI(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP);
        BigDecimal rsi = BigDecimal.valueOf(100).subtract(
            BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 4, RoundingMode.HALF_UP)
        );
        return rsi.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal current(List<Candle> candles) {
        List<BigDecimal> values = calculate(candles);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    public String signal(BigDecimal rsiValue) {
        if (rsiValue == null) return "NEUTRAL";
        if (rsiValue.compareTo(oversold) < 0) return "OVERSOLD";
        if (rsiValue.compareTo(overbought) > 0) return "OVERBOUGHT";
        if (rsiValue.compareTo(neutral) < 0) return "WEAK";
        return "NEUTRAL";
    }
}
