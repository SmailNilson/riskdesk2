package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * RSI series paired with a simple moving average computed on the RSI itself.
 * Used by strategies that key off the RSI / SMA(RSI) crossover (e.g. WTX+RSI).
 *
 * The RSI math is delegated to {@link RSIIndicator} so the two implementations
 * cannot drift apart. This class only adds the SMA layer and the bar-aligned
 * cross series.
 */
public final class RsiWithSma {

    private final int rsiPeriod;
    private final int smaPeriod;
    private final RSIIndicator rsi;

    public RsiWithSma(int rsiPeriod, int smaPeriod) {
        if (rsiPeriod <= 0) throw new IllegalArgumentException("rsiPeriod must be > 0");
        if (smaPeriod <= 0) throw new IllegalArgumentException("smaPeriod must be > 0");
        this.rsiPeriod = rsiPeriod;
        this.smaPeriod = smaPeriod;
        this.rsi = new RSIIndicator(rsiPeriod, 33.0, 40.0, 60.0);
    }

    public RsiWithSma() {
        this(14, 14);
    }

    /**
     * Returns one Sample per candle, **left-padded with nulls** so the index
     * of each Sample matches the index of its source candle. Callers can then
     * iterate the candles and the samples in lockstep without offset bookkeeping.
     */
    public List<Sample> calculate(List<Candle> candles) {
        Objects.requireNonNull(candles, "candles");
        int n = candles.size();
        List<Sample> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(Sample.empty());

        List<BigDecimal> rsiValues = rsi.calculate(candles);
        // RSIIndicator returns one value starting from index `rsiPeriod` (first usable bar
        // after the warmup), so rsiValues.size() == n - rsiPeriod when n > rsiPeriod.
        if (rsiValues.isEmpty()) return out;

        int firstRsiIdx = n - rsiValues.size();
        // Build SMA(RSI) on the rsiValues series; SMA needs `smaPeriod` samples to start.
        for (int j = 0; j < rsiValues.size(); j++) {
            int candleIdx = firstRsiIdx + j;
            BigDecimal rsiV = rsiValues.get(j);

            BigDecimal smaV = null;
            if (j + 1 >= smaPeriod) {
                BigDecimal sum = BigDecimal.ZERO;
                for (int k = j + 1 - smaPeriod; k <= j; k++) {
                    sum = sum.add(rsiValues.get(k));
                }
                smaV = sum.divide(BigDecimal.valueOf(smaPeriod), 4, RoundingMode.HALF_UP);
            }

            Sample prev = (candleIdx > 0) ? out.get(candleIdx - 1) : Sample.empty();
            CrossDirection cross = CrossDirection.NONE;
            if (smaV != null && prev != null && prev.rsi() != null && prev.sma() != null) {
                int prevSign = prev.rsi().compareTo(prev.sma());
                int curSign = rsiV.compareTo(smaV);
                if (prevSign <= 0 && curSign > 0) cross = CrossDirection.UP;
                else if (prevSign >= 0 && curSign < 0) cross = CrossDirection.DOWN;
            }
            out.set(candleIdx, new Sample(rsiV, smaV, cross));
        }
        return out;
    }

    public Sample current(List<Candle> candles) {
        List<Sample> all = calculate(candles);
        return all.isEmpty() ? Sample.empty() : all.get(all.size() - 1);
    }

    public int rsiPeriod() { return rsiPeriod; }
    public int smaPeriod() { return smaPeriod; }

    public enum CrossDirection { NONE, UP, DOWN }

    public record Sample(BigDecimal rsi, BigDecimal sma, CrossDirection cross) {
        public static Sample empty() { return new Sample(null, null, CrossDirection.NONE); }
        public boolean isReady() { return rsi != null && sma != null; }
    }
}
