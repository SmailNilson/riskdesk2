package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * WaveTrend Oscillator — a momentum indicator that identifies overbought/oversold
 * conditions and generates crossover signals.
 *
 * Algorithm (LazyBear / TradingView):
 *   src  = HLC3 = (high + low + close) / 3
 *   esa  = EMA(src, n1)
 *   d    = EMA(|src - esa|, n1)
 *   ci   = (src - esa) / (0.015 * d)     — normalized channel index
 *   wt1  = EMA(ci, n2)                    — WaveTrend line
 *   wt2  = SMA(wt1, signalPeriod)         — signal line
 *
 * Defaults: n1=10, n2=21, signalPeriod=4
 * WT_X levels: overbought >= 53, oversold <= -53
 */
public class WaveTrendIndicator implements TechnicalIndicator<WaveTrendIndicator.WaveTrendResult> {

    private final int n1;
    private final int n2;
    private final int signalPeriod;

    private static final BigDecimal OB_LEVEL = BigDecimal.valueOf(53);
    private static final BigDecimal OS_LEVEL = BigDecimal.valueOf(-53);
    private static final BigDecimal FACTOR   = new BigDecimal("0.015");
    private static final int        SCALE    = 6;

    public record WaveTrendResult(
            BigDecimal wt1,
            BigDecimal wt2,
            BigDecimal diff,
            String crossover,   // BULLISH_CROSS | BEARISH_CROSS | null
            String signal       // OVERBOUGHT | OVERSOLD | NEUTRAL
    ) {}

    public WaveTrendIndicator(int n1, int n2, int signalPeriod) {
        this.n1 = n1;
        this.n2 = n2;
        this.signalPeriod = signalPeriod;
    }

    public WaveTrendIndicator() {
        this(10, 21, 4);
    }

    @Override
    public List<WaveTrendResult> calculate(List<Candle> candles) {
        if (candles.isEmpty()) return Collections.emptyList();

        BigDecimal esaMult  = BigDecimal.valueOf(2.0 / (n1 + 1));
        BigDecimal wt1Mult  = BigDecimal.valueOf(2.0 / (n2 + 1));

        BigDecimal esaEma  = null;
        BigDecimal dEma    = null;
        BigDecimal wt1Ema  = null;
        BigDecimal prevWt1 = null;
        BigDecimal prevWt2 = null;

        Deque<BigDecimal> wt1Window = new ArrayDeque<>(signalPeriod);
        List<WaveTrendResult> results = new ArrayList<>();

        for (Candle c : candles) {
            // HLC3
            BigDecimal src = c.getHigh().add(c.getLow()).add(c.getClose())
                    .divide(BigDecimal.valueOf(3), SCALE, RoundingMode.HALF_UP);

            // ESA = EMA(src, n1)
            esaEma = (esaEma == null) ? src
                    : src.subtract(esaEma).multiply(esaMult).add(esaEma)
                         .setScale(SCALE, RoundingMode.HALF_UP);

            // d = EMA(|src - esa|, n1)
            BigDecimal dev = src.subtract(esaEma).abs();
            dEma = (dEma == null) ? dev
                    : dev.subtract(dEma).multiply(esaMult).add(dEma)
                         .setScale(SCALE, RoundingMode.HALF_UP);

            // CI = (src - esa) / (0.015 * d)
            BigDecimal denom = FACTOR.multiply(dEma);
            BigDecimal ci = denom.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : src.subtract(esaEma).divide(denom, SCALE, RoundingMode.HALF_UP);

            // WT1 = EMA(ci, n2)
            wt1Ema = (wt1Ema == null) ? ci
                    : ci.subtract(wt1Ema).multiply(wt1Mult).add(wt1Ema)
                        .setScale(SCALE, RoundingMode.HALF_UP);

            // WT2 = SMA(wt1, signalPeriod)
            wt1Window.addLast(wt1Ema);
            if (wt1Window.size() > signalPeriod) wt1Window.pollFirst();
            if (wt1Window.size() < signalPeriod) {
                prevWt1 = wt1Ema;
                continue;
            }

            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal v : wt1Window) sum = sum.add(v);
            BigDecimal wt2 = sum.divide(BigDecimal.valueOf(signalPeriod), SCALE, RoundingMode.HALF_UP);
            BigDecimal wt1Scaled = wt1Ema.setScale(4, RoundingMode.HALF_UP);
            BigDecimal wt2Scaled = wt2.setScale(4, RoundingMode.HALF_UP);
            BigDecimal diff = wt1Scaled.subtract(wt2Scaled);

            // Crossover vs previous bar
            String crossover = null;
            if (prevWt1 != null && prevWt2 != null) {
                boolean crossedAbove = prevWt1.compareTo(prevWt2) <= 0 && wt1Ema.compareTo(wt2) > 0;
                boolean crossedBelow = prevWt1.compareTo(prevWt2) >= 0 && wt1Ema.compareTo(wt2) < 0;
                if (crossedAbove) crossover = "BULLISH_CROSS";
                else if (crossedBelow) crossover = "BEARISH_CROSS";
            }

            // Signal level
            String signal;
            if (wt1Ema.compareTo(OB_LEVEL) > 0) signal = "OVERBOUGHT";
            else if (wt1Ema.compareTo(OS_LEVEL) < 0) signal = "OVERSOLD";
            else signal = "NEUTRAL";

            results.add(new WaveTrendResult(wt1Scaled, wt2Scaled, diff, crossover, signal));

            prevWt1 = wt1Ema;
            prevWt2 = wt2;
        }

        return results;
    }

    @Override
    public WaveTrendResult current(List<Candle> candles) {
        List<WaveTrendResult> results = calculate(candles);
        return results.isEmpty() ? null : results.get(results.size() - 1);
    }
}
