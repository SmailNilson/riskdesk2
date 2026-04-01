package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.shared.TradingSessionResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * Session VWAP — Volume Weighted Average Price, resets on the CME trading-session
 * boundary (17:00 ET), not at calendar midnight.
 */
public class VWAPIndicator implements TechnicalIndicator<VWAPIndicator.VWAPResult> {

    public record VWAPResult(BigDecimal vwap, BigDecimal upperBand, BigDecimal lowerBand) {}

    /**
     * Calculate VWAP across candles, resetting at each CME session boundary.
     * Bands = VWAP +/- stddev of typical price.
     */
    public List<VWAPResult> calculate(List<Candle> candles) {
        if (candles.isEmpty()) return Collections.emptyList();

        List<VWAPResult> results = new ArrayList<>();
        BigDecimal cumulativeTPV = BigDecimal.ZERO; // sum(TP * Volume)
        long cumulativeVolume = 0;
        List<BigDecimal> typicalPrices = new ArrayList<>();
        Instant currentSession = null;

        for (Candle c : candles) {
            // Candle timestamps represent the bar close in this codebase.
            // A bar stamped exactly at 17:00 ET is the final bar of the session that just ended,
            // so we resolve the session using the instant immediately before that close.
            Instant sessionReference = c.getTimestamp().minusNanos(1);
            Instant candleSession = TradingSessionResolver.dailySessionStart(sessionReference);
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
