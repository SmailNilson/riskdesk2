package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Session VWAP — Volume Weighted Average Price, resets daily.
 */
public class VWAPIndicator implements TechnicalIndicator<VWAPIndicator.VWAPResult> {

    private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

    public record VWAPResult(BigDecimal vwap, BigDecimal upperBand, BigDecimal lowerBand) {}

    /**
     * Calculate VWAP across candles, resetting at each market session date.
     * Bands = VWAP +/- stddev of typical price.
     */
    public List<VWAPResult> calculate(List<Candle> candles) {
        if (candles.isEmpty()) return Collections.emptyList();

        List<VWAPResult> results = new ArrayList<>();
        BigDecimal cumulativeTPV = BigDecimal.ZERO; // sum(TP * Volume)
        long cumulativeVolume = 0;
        List<BigDecimal> typicalPrices = new ArrayList<>();
        LocalDate currentSession = null;

        for (Candle c : candles) {
            LocalDate candleSession = c.getTimestamp().atZone(MARKET_TZ).toLocalDate();
            if (currentSession != null && !currentSession.equals(candleSession)) {
                cumulativeTPV = BigDecimal.ZERO;
                cumulativeVolume = 0;
                typicalPrices = new ArrayList<>();
            }
            currentSession = candleSession;

            BigDecimal tp = c.getHigh().add(c.getLow()).add(c.getClose())
                    .divide(BigDecimal.valueOf(3), 10, RoundingMode.HALF_UP);
            typicalPrices.add(tp);

            cumulativeTPV = cumulativeTPV.add(tp.multiply(BigDecimal.valueOf(c.getVolume())));
            cumulativeVolume += c.getVolume();

            if (cumulativeVolume == 0) {
                results.add(new VWAPResult(tp, tp, tp));
                continue;
            }

            BigDecimal vwap = cumulativeTPV.divide(BigDecimal.valueOf(cumulativeVolume), 5, RoundingMode.HALF_UP);

            // Standard deviation of TP from VWAP
            BigDecimal sumSqDiff = BigDecimal.ZERO;
            for (BigDecimal prevTp : typicalPrices) {
                BigDecimal diff = prevTp.subtract(vwap);
                sumSqDiff = sumSqDiff.add(diff.multiply(diff));
            }
            BigDecimal variance = sumSqDiff.divide(BigDecimal.valueOf(typicalPrices.size()), 10, RoundingMode.HALF_UP);
            BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

            results.add(new VWAPResult(
                    vwap,
                    vwap.add(stdDev.multiply(BigDecimal.TWO)).setScale(5, RoundingMode.HALF_UP),
                    vwap.subtract(stdDev.multiply(BigDecimal.TWO)).setScale(5, RoundingMode.HALF_UP)
            ));
        }

        return results;
    }

    public VWAPResult current(List<Candle> candles) {
        List<VWAPResult> values = calculate(candles);
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }
}
