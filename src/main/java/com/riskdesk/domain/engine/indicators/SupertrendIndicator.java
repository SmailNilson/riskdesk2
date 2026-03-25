package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Supertrend indicator (period 10, factor 3).
 * Outputs trend direction + support/resistance levels.
 */
public class SupertrendIndicator implements TechnicalIndicator<SupertrendIndicator.SupertrendResult> {

    private final int atrPeriod;
    private final BigDecimal factor;

    public SupertrendIndicator(int atrPeriod, double factor) {
        this.atrPeriod = atrPeriod;
        this.factor = BigDecimal.valueOf(factor);
    }

    public SupertrendIndicator() {
        this(10, 3.0);
    }

    public record SupertrendResult(BigDecimal value, boolean isUptrend, BigDecimal upperBand, BigDecimal lowerBand) {}

    public List<SupertrendResult> calculate(List<Candle> candles) {
        if (candles.size() <= atrPeriod) return Collections.emptyList();

        // Calculate ATR
        List<BigDecimal> trueRanges = new ArrayList<>();
        trueRanges.add(candles.get(0).range());

        for (int i = 1; i < candles.size(); i++) {
            BigDecimal high = candles.get(i).getHigh();
            BigDecimal low = candles.get(i).getLow();
            BigDecimal prevClose = candles.get(i - 1).getClose();

            BigDecimal tr = high.subtract(low)
                    .max(high.subtract(prevClose).abs())
                    .max(low.subtract(prevClose).abs());
            trueRanges.add(tr);
        }

        // ATR with Wilder's smoothing
        List<BigDecimal> atr = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < atrPeriod; i++) {
            sum = sum.add(trueRanges.get(i));
        }
        BigDecimal currentAtr = sum.divide(BigDecimal.valueOf(atrPeriod), 10, RoundingMode.HALF_UP);
        atr.add(currentAtr);

        BigDecimal periodDec = BigDecimal.valueOf(atrPeriod);
        for (int i = atrPeriod; i < trueRanges.size(); i++) {
            currentAtr = currentAtr.multiply(periodDec.subtract(BigDecimal.ONE))
                    .add(trueRanges.get(i))
                    .divide(periodDec, 10, RoundingMode.HALF_UP);
            atr.add(currentAtr);
        }

        // Supertrend calculation
        List<SupertrendResult> results = new ArrayList<>();
        int startIdx = atrPeriod;

        BigDecimal hl2 = candles.get(startIdx).getHigh().add(candles.get(startIdx).getLow())
                .divide(BigDecimal.TWO, 10, RoundingMode.HALF_UP);
        BigDecimal atrVal = atr.get(0);

        BigDecimal upperBand = hl2.add(factor.multiply(atrVal));
        BigDecimal lowerBand = hl2.subtract(factor.multiply(atrVal));
        BigDecimal prevUpperBand = upperBand;
        BigDecimal prevLowerBand = lowerBand;
        boolean isUptrend = true;

        results.add(new SupertrendResult(lowerBand, true, upperBand, lowerBand));

        for (int i = startIdx + 1; i < candles.size(); i++) {
            hl2 = candles.get(i).getHigh().add(candles.get(i).getLow())
                    .divide(BigDecimal.TWO, 10, RoundingMode.HALF_UP);
            atrVal = atr.get(i - atrPeriod);

            BigDecimal newUpper = hl2.add(factor.multiply(atrVal));
            BigDecimal newLower = hl2.subtract(factor.multiply(atrVal));

            // Band clamping
            if (newLower.compareTo(prevLowerBand) > 0 ||
                    candles.get(i - 1).getClose().compareTo(prevLowerBand) < 0) {
                lowerBand = newLower;
            } else {
                lowerBand = prevLowerBand;
            }

            if (newUpper.compareTo(prevUpperBand) < 0 ||
                    candles.get(i - 1).getClose().compareTo(prevUpperBand) > 0) {
                upperBand = newUpper;
            } else {
                upperBand = prevUpperBand;
            }

            // Direction
            BigDecimal close = candles.get(i).getClose();
            if (isUptrend && close.compareTo(lowerBand) < 0) {
                isUptrend = false;
            } else if (!isUptrend && close.compareTo(upperBand) > 0) {
                isUptrend = true;
            }

            BigDecimal supertrendValue = isUptrend ? lowerBand : upperBand;
            results.add(new SupertrendResult(
                    supertrendValue.setScale(5, RoundingMode.HALF_UP),
                    isUptrend,
                    upperBand.setScale(5, RoundingMode.HALF_UP),
                    lowerBand.setScale(5, RoundingMode.HALF_UP)
            ));

            prevUpperBand = upperBand;
            prevLowerBand = lowerBand;
        }

        return results;
    }

    public SupertrendResult current(List<Candle> candles) {
        List<SupertrendResult> values = calculate(candles);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }
}
