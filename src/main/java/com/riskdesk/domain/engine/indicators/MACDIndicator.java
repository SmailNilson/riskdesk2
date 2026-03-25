package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * MACD (12, 26, 9) with histogram and signal crossover detection.
 */
public class MACDIndicator implements TechnicalIndicator<MACDIndicator.MACDResult> {

    private final EMAIndicator fastEma;
    private final EMAIndicator slowEma;
    private final int signalPeriod;

    public MACDIndicator(int fastPeriod, int slowPeriod, int signalPeriod) {
        if (fastPeriod >= slowPeriod) {
            throw new IllegalArgumentException(
                "MACD fastPeriod (" + fastPeriod + ") must be less than slowPeriod (" + slowPeriod + ")");
        }
        this.fastEma = new EMAIndicator(fastPeriod);
        this.slowEma = new EMAIndicator(slowPeriod);
        this.signalPeriod = signalPeriod;
    }

    public MACDIndicator() {
        this(12, 26, 9);
    }

    public record MACDResult(BigDecimal macdLine, BigDecimal signalLine, BigDecimal histogram) {}

    public List<MACDResult> calculate(List<Candle> candles) {
        List<BigDecimal> fastValues = fastEma.calculate(candles);
        List<BigDecimal> slowValues = slowEma.calculate(candles);

        if (fastValues.isEmpty() || slowValues.isEmpty()) return Collections.emptyList();

        // Align: slow EMA starts later, so fast needs offset
        int offset = slowEma.getPeriod() - fastEma.getPeriod();
        List<BigDecimal> macdLine = new ArrayList<>();

        for (int i = 0; i < slowValues.size(); i++) {
            BigDecimal fast = fastValues.get(i + offset);
            BigDecimal slow = slowValues.get(i);
            macdLine.add(fast.subtract(slow).setScale(5, RoundingMode.HALF_UP));
        }

        // Signal line = EMA(signalPeriod) of MACD line
        if (macdLine.size() < signalPeriod) return Collections.emptyList();

        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (signalPeriod + 1));
        List<MACDResult> results = new ArrayList<>();

        // Seed signal with SMA
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < signalPeriod; i++) {
            sum = sum.add(macdLine.get(i));
        }
        BigDecimal signal = sum.divide(BigDecimal.valueOf(signalPeriod), 10, RoundingMode.HALF_UP);

        BigDecimal macd = macdLine.get(signalPeriod - 1);
        results.add(new MACDResult(macd, signal.setScale(5, RoundingMode.HALF_UP),
                macd.subtract(signal).setScale(5, RoundingMode.HALF_UP)));

        // EMA smoothing for signal
        for (int i = signalPeriod; i < macdLine.size(); i++) {
            macd = macdLine.get(i);
            signal = macd.subtract(signal).multiply(multiplier).add(signal);
            signal = signal.setScale(5, RoundingMode.HALF_UP);
            results.add(new MACDResult(macd, signal, macd.subtract(signal).setScale(5, RoundingMode.HALF_UP)));
        }

        return results;
    }

    public MACDResult current(List<Candle> candles) {
        List<MACDResult> values = calculate(candles);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    /**
     * Detect MACD/signal crossover.
     */
    public String detectCrossover(List<MACDResult> results) {
        if (results.size() < 2) return null;
        MACDResult curr = results.get(results.size() - 1);
        MACDResult prev = results.get(results.size() - 2);

        boolean crossedAbove = prev.histogram().compareTo(BigDecimal.ZERO) <= 0
                && curr.histogram().compareTo(BigDecimal.ZERO) > 0;
        boolean crossedBelow = prev.histogram().compareTo(BigDecimal.ZERO) >= 0
                && curr.histogram().compareTo(BigDecimal.ZERO) < 0;

        if (crossedAbove) return "BULLISH_CROSS";
        if (crossedBelow) return "BEARISH_CROSS";
        return null;
    }
}
