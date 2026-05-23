package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic synthetic OHLCV used across the WTX+RSI test suite.
 *
 * The series is a slow sine wave with bounded gaussian noise, designed to
 * make the WaveTrend visit both OB and OS zones and to produce RSI/SMA
 * crosses in both directions during the lookback window.
 */
final class SyntheticCandles {

    private SyntheticCandles() {}

    static List<Candle> mnq(int n, long seed) {
        return mnq(n, seed, 17000.0);
    }

    static List<Candle> mnq(int n, long seed, double base) {
        Random rng = new Random(seed);
        List<Candle> out = new ArrayList<>(n);
        Instant ts = Instant.parse("2025-01-02T14:30:00Z");
        double prevClose = base;
        for (int i = 0; i < n; i++) {
            double phase = (i / (double) n) * 6.0 * Math.PI;
            double mid = base + 80.0 * Math.sin(phase) + (i * 40.0 / n);
            double close = mid + (rng.nextGaussian() * 4.0);
            double open = prevClose;
            double wick = 2.0 + (rng.nextDouble() * 6.0);
            double high = Math.max(open, close) + wick;
            double low = Math.min(open, close) - wick;
            long volume = 500 + rng.nextInt(4500);
            Candle c = new Candle(
                    Instrument.MNQ, "5m",
                    ts.plus(i * 5L, ChronoUnit.MINUTES),
                    BigDecimal.valueOf(Math.round(open * 100.0) / 100.0),
                    BigDecimal.valueOf(Math.round(high * 100.0) / 100.0),
                    BigDecimal.valueOf(Math.round(low * 100.0) / 100.0),
                    BigDecimal.valueOf(Math.round(close * 100.0) / 100.0),
                    volume
            );
            out.add(c);
            prevClose = close;
        }
        return out;
    }
}
