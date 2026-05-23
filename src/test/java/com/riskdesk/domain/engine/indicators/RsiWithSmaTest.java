package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.engine.indicators.RsiWithSma.CrossDirection;
import com.riskdesk.domain.engine.indicators.RsiWithSma.Sample;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RsiWithSmaTest {

    @Test
    void aligns_samples_to_candle_indices() {
        List<Candle> candles = buildOscillating(200, 1);
        List<Sample> samples = new RsiWithSma(14, 14).calculate(candles);
        assertEquals(candles.size(), samples.size(),
                "samples must be left-padded to candle count for index alignment");
        long readyCount = samples.stream().filter(Sample::isReady).count();
        assertTrue(readyCount > 100,
                "expected RSI+SMA to be ready for the bulk of a 200-bar series, got " + readyCount);
    }

    @Test
    void emits_both_cross_directions_on_oscillating_series() {
        // Noisy sine — RSI oscillates around its SMA so both crosses appear.
        List<Candle> candles = buildOscillating(200, 42);
        List<Sample> samples = new RsiWithSma(14, 14).calculate(candles);
        long ups = samples.stream().filter(s -> s.cross() == CrossDirection.UP).count();
        long downs = samples.stream().filter(s -> s.cross() == CrossDirection.DOWN).count();
        assertTrue(ups > 0, "expected at least one cross-up");
        assertTrue(downs > 0, "expected at least one cross-down");
    }

    @Test
    void crosses_are_bar_aligned_with_sign_flip() {
        List<Candle> candles = buildOscillating(200, 7);
        List<Sample> samples = new RsiWithSma(14, 14).calculate(candles);
        for (int i = 1; i < samples.size(); i++) {
            Sample cur = samples.get(i);
            Sample prev = samples.get(i - 1);
            if (cur.cross() == CrossDirection.UP) {
                assertTrue(prev.rsi().compareTo(prev.sma()) <= 0
                        && cur.rsi().compareTo(cur.sma()) > 0,
                        "UP cross at bar " + i + " must show a strict sign flip");
            } else if (cur.cross() == CrossDirection.DOWN) {
                assertTrue(prev.rsi().compareTo(prev.sma()) >= 0
                        && cur.rsi().compareTo(cur.sma()) < 0,
                        "DOWN cross at bar " + i + " must show a strict sign flip");
            }
        }
    }

    private static List<Candle> buildOscillating(int n, long seed) {
        Random rng = new Random(seed);
        List<Candle> out = new ArrayList<>(n);
        Instant ts = Instant.parse("2025-01-02T14:30:00Z");
        double prev = 100.0;
        for (int i = 0; i < n; i++) {
            // Sine driver + Gaussian noise — keeps RSI bouncing around its SMA.
            double close = 100.0 + 5.0 * Math.sin(i * 0.35) + rng.nextGaussian() * 1.5;
            BigDecimal hi = BigDecimal.valueOf(Math.max(prev, close) + 0.5);
            BigDecimal lo = BigDecimal.valueOf(Math.min(prev, close) - 0.5);
            out.add(new Candle(Instrument.MNQ, "1m", ts.plus(i, ChronoUnit.MINUTES),
                    BigDecimal.valueOf(prev), hi, lo, BigDecimal.valueOf(close), 1000));
            prev = close;
        }
        return out;
    }
}
