package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Exponential Moving Average calculator.
 * Supports EMA 9, 50, 200 with crossover detection (golden cross / death cross).
 */
public class EMAIndicator implements TechnicalIndicator<BigDecimal> {

    private final int period;
    private final BigDecimal multiplier;

    public EMAIndicator(int period) {
        this.period = period;
        this.multiplier = BigDecimal.valueOf(2.0 / (period + 1));
    }

    /**
     * Calculate EMA for a series of candles. Returns one result per candle (after warmup).
     */
    public List<BigDecimal> calculate(List<Candle> candles) {
        if (candles.size() < period) return Collections.emptyList();

        List<BigDecimal> results = new ArrayList<>();

        // SMA for the initial seed
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(candles.get(i).getClose());
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
        results.add(ema);

        // EMA from period onwards
        for (int i = period; i < candles.size(); i++) {
            BigDecimal close = candles.get(i).getClose();
            ema = close.subtract(ema).multiply(multiplier).add(ema);
            ema = ema.setScale(5, RoundingMode.HALF_UP);
            results.add(ema);
        }

        return results;
    }

    /**
     * Get current EMA value from the latest candles.
     */
    public BigDecimal current(List<Candle> candles) {
        List<BigDecimal> emas = calculate(candles);
        return emas.isEmpty() ? null : emas.get(emas.size() - 1);
    }

    /**
     * Detect crossovers between fast and slow EMA.
     * Returns "GOLDEN_CROSS", "DEATH_CROSS", or null.
     */
    public static String detectCrossover(List<BigDecimal> fastEma, List<BigDecimal> slowEma) {
        if (fastEma.size() < 2 || slowEma.size() < 2) return null;

        int len = Math.min(fastEma.size(), slowEma.size());
        BigDecimal fastCurr = fastEma.get(len - 1);
        BigDecimal fastPrev = fastEma.get(len - 2);
        BigDecimal slowCurr = slowEma.get(len - 1);
        BigDecimal slowPrev = slowEma.get(len - 2);

        boolean crossedAbove = fastPrev.compareTo(slowPrev) <= 0 && fastCurr.compareTo(slowCurr) > 0;
        boolean crossedBelow = fastPrev.compareTo(slowPrev) >= 0 && fastCurr.compareTo(slowCurr) < 0;

        if (crossedAbove) return "GOLDEN_CROSS";
        if (crossedBelow) return "DEATH_CROSS";
        return null;
    }

    public int getPeriod() { return period; }
}
