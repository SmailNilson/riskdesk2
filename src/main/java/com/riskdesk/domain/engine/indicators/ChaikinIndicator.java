package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Chaikin Oscillator (3, 10) — measures momentum of Accumulation/Distribution Line.
 * Also includes CMF (Chaikin Money Flow, period 20).
 */
public class ChaikinIndicator {

    private final int fastPeriod;
    private final int slowPeriod;
    private final int cmfPeriod;

    public ChaikinIndicator(int fastPeriod, int slowPeriod, int cmfPeriod) {
        if (fastPeriod >= slowPeriod) {
            throw new IllegalArgumentException(
                "Chaikin fastPeriod (" + fastPeriod + ") must be less than slowPeriod (" + slowPeriod + ")");
        }
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.cmfPeriod = cmfPeriod;
    }

    public ChaikinIndicator() {
        this(3, 10, 20);
    }

    /**
     * Money Flow Multiplier = ((Close - Low) - (High - Close)) / (High - Low)
     */
    private BigDecimal moneyFlowMultiplier(Candle c) {
        BigDecimal range = c.getHigh().subtract(c.getLow());
        if (range.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        return c.getClose().subtract(c.getLow())
                .subtract(c.getHigh().subtract(c.getClose()))
                .divide(range, 10, RoundingMode.HALF_UP);
    }

    /**
     * Money Flow Volume = MF Multiplier * Volume
     */
    private BigDecimal moneyFlowVolume(Candle c) {
        return moneyFlowMultiplier(c).multiply(BigDecimal.valueOf(c.getVolume()));
    }

    /**
     * Accumulation/Distribution Line
     */
    public List<BigDecimal> adLine(List<Candle> candles) {
        List<BigDecimal> ad = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (Candle c : candles) {
            cumulative = cumulative.add(moneyFlowVolume(c));
            ad.add(cumulative.setScale(2, RoundingMode.HALF_UP));
        }
        return ad;
    }

    /**
     * Chaikin Oscillator = EMA(3) of AD - EMA(10) of AD
     */
    public List<BigDecimal> calculateOscillator(List<Candle> candles) {
        List<BigDecimal> ad = adLine(candles);
        if (ad.size() < slowPeriod) return Collections.emptyList();

        List<BigDecimal> fastEma = emaOfSeries(ad, fastPeriod);
        List<BigDecimal> slowEma = emaOfSeries(ad, slowPeriod);

        int offset = slowPeriod - fastPeriod;
        List<BigDecimal> result = new ArrayList<>();
        for (int i = 0; i < slowEma.size(); i++) {
            result.add(fastEma.get(i + offset).subtract(slowEma.get(i)).setScale(2, RoundingMode.HALF_UP));
        }
        return result;
    }

    /**
     * Chaikin Money Flow (CMF) = sum(MF Volume, period) / sum(Volume, period)
     */
    public List<BigDecimal> calculateCMF(List<Candle> candles) {
        if (candles.size() < cmfPeriod) return Collections.emptyList();

        List<BigDecimal> result = new ArrayList<>();
        for (int i = cmfPeriod - 1; i < candles.size(); i++) {
            BigDecimal sumMfv = BigDecimal.ZERO;
            long sumVol = 0;
            for (int j = i - cmfPeriod + 1; j <= i; j++) {
                sumMfv = sumMfv.add(moneyFlowVolume(candles.get(j)));
                sumVol += candles.get(j).getVolume();
            }
            if (sumVol == 0) {
                result.add(BigDecimal.ZERO);
            } else {
                result.add(sumMfv.divide(BigDecimal.valueOf(sumVol), 4, RoundingMode.HALF_UP));
            }
        }
        return result;
    }

    private List<BigDecimal> emaOfSeries(List<BigDecimal> data, int period) {
        if (data.size() < period) return Collections.emptyList();
        BigDecimal multiplier = BigDecimal.valueOf(2.0 / (period + 1));
        List<BigDecimal> result = new ArrayList<>();

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) sum = sum.add(data.get(i));
        BigDecimal ema = sum.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
        result.add(ema);

        for (int i = period; i < data.size(); i++) {
            ema = data.get(i).subtract(ema).multiply(multiplier).add(ema);
            result.add(ema.setScale(5, RoundingMode.HALF_UP));
        }
        return result;
    }
}
