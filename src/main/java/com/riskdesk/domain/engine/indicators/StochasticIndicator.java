package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Stochastic Oscillator (%K / %D).
 *
 * %K = 100 × (Close - Lowest Low over K period) / (Highest High - Lowest Low)
 * %D = SMA(%K, D period)
 *
 * Defaults: kPeriod=14, dPeriod=3, slowing=3 (Slow Stochastic).
 * OB/OS levels: overbought >= 80, oversold <= 20.
 */
public class StochasticIndicator implements TechnicalIndicator<StochasticIndicator.StochasticResult> {

    private final int kPeriod;
    private final int dPeriod;
    private final int slowing;

    private static final BigDecimal OB_LEVEL = BigDecimal.valueOf(80);
    private static final BigDecimal OS_LEVEL = BigDecimal.valueOf(20);
    private static final int SCALE = 6;

    public record StochasticResult(
            BigDecimal k,
            BigDecimal d,
            String signal,      // OVERBOUGHT | OVERSOLD | NEUTRAL
            String crossover    // BULLISH_CROSS | BEARISH_CROSS | null
    ) {}

    public StochasticIndicator(int kPeriod, int dPeriod, int slowing) {
        if (kPeriod < 1 || dPeriod < 1 || slowing < 1) {
            throw new IllegalArgumentException("All periods must be >= 1");
        }
        this.kPeriod = kPeriod;
        this.dPeriod = dPeriod;
        this.slowing = slowing;
    }

    public StochasticIndicator() {
        this(14, 3, 3);
    }

    @Override
    public List<StochasticResult> calculate(List<Candle> candles) {
        int minBars = kPeriod + slowing - 1;
        if (candles.size() < minBars) return Collections.emptyList();

        // Step 1: Compute raw %K for each bar (from index kPeriod-1 onward)
        List<BigDecimal> rawK = new ArrayList<>();
        for (int i = kPeriod - 1; i < candles.size(); i++) {
            BigDecimal highestHigh = candles.get(i).getHigh();
            BigDecimal lowestLow = candles.get(i).getLow();
            for (int j = i - kPeriod + 1; j < i; j++) {
                BigDecimal h = candles.get(j).getHigh();
                BigDecimal l = candles.get(j).getLow();
                if (h.compareTo(highestHigh) > 0) highestHigh = h;
                if (l.compareTo(lowestLow) < 0) lowestLow = l;
            }
            BigDecimal range = highestHigh.subtract(lowestLow);
            BigDecimal close = candles.get(i).getClose();
            BigDecimal k;
            if (range.compareTo(BigDecimal.ZERO) == 0) {
                k = BigDecimal.valueOf(50); // mid-range when flat
            } else {
                k = close.subtract(lowestLow)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(range, SCALE, RoundingMode.HALF_UP);
            }
            rawK.add(k);
        }

        // Step 2: Slow %K = SMA(rawK, slowing)
        List<BigDecimal> slowK = sma(rawK, slowing);

        // Step 3: %D = SMA(slowK, dPeriod)
        List<BigDecimal> dLine = sma(slowK, dPeriod);

        // Step 4: Build results aligned with dLine
        List<StochasticResult> results = new ArrayList<>();
        BigDecimal prevK = null;
        BigDecimal prevD = null;

        int kOffset = slowK.size() - dLine.size(); // offset of dLine[0] within slowK
        for (int i = 0; i < dLine.size(); i++) {
            BigDecimal k = slowK.get(i + kOffset);
            BigDecimal d = dLine.get(i);

            String signal;
            if (k.compareTo(OB_LEVEL) >= 0) signal = "OVERBOUGHT";
            else if (k.compareTo(OS_LEVEL) <= 0) signal = "OVERSOLD";
            else signal = "NEUTRAL";

            String crossover = null;
            if (prevK != null && prevD != null) {
                boolean crossedAbove = prevK.compareTo(prevD) <= 0 && k.compareTo(d) > 0;
                boolean crossedBelow = prevK.compareTo(prevD) >= 0 && k.compareTo(d) < 0;
                if (crossedAbove) crossover = "BULLISH_CROSS";
                else if (crossedBelow) crossover = "BEARISH_CROSS";
            }

            results.add(new StochasticResult(
                    k.setScale(SCALE, RoundingMode.HALF_UP),
                    d.setScale(SCALE, RoundingMode.HALF_UP),
                    signal, crossover));

            prevK = k;
            prevD = d;
        }

        return results;
    }

    @Override
    public StochasticResult current(List<Candle> candles) {
        List<StochasticResult> results = calculate(candles);
        return results.isEmpty() ? null : results.get(results.size() - 1);
    }

    private static List<BigDecimal> sma(List<BigDecimal> values, int period) {
        if (values.size() < period) return Collections.emptyList();
        List<BigDecimal> result = new ArrayList<>();
        BigDecimal window = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) window = window.add(values.get(i));
        result.add(window.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP));
        for (int i = period; i < values.size(); i++) {
            window = window.add(values.get(i)).subtract(values.get(i - period));
            result.add(window.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP));
        }
        return result;
    }
}
