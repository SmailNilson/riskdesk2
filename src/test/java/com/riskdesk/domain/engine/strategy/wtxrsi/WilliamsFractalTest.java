package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WilliamsFractal.Fractal;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WilliamsFractalTest {

    @Test
    void detects_confirmed_fractal_low() {
        List<Candle> candles = candles(new double[][]{
                {100, 101, 99,  100},
                {99,  100, 98,  99},
                {98,  99,  95,  96.5}, // pivot low at index 2 (95)
                {97,  99,  96,  98},
                {98,  101, 97,  100}
        });
        Optional<Fractal> low = WilliamsFractal.findMostRecentConfirmedLow(candles, 2, 10);
        assertTrue(low.isPresent());
        assertEquals(2, low.get().barIndex());
        assertEquals(Fractal.Kind.LOW, low.get().kind());
        assertEquals(0, low.get().price().compareTo(new BigDecimal("95")));
    }

    @Test
    void detects_confirmed_fractal_high() {
        List<Candle> candles = candles(new double[][]{
                {100, 101, 99,  100},
                {101, 102, 100, 101.5},
                {102, 110, 101, 105},   // pivot high at index 2 (110)
                {104, 106, 102, 103},
                {103, 105, 100, 101}
        });
        Optional<Fractal> hi = WilliamsFractal.findMostRecentConfirmedHigh(candles, 2, 10);
        assertTrue(hi.isPresent());
        assertEquals(2, hi.get().barIndex());
        assertEquals(0, hi.get().price().compareTo(new BigDecimal("110")));
    }

    @Test
    void unconfirmed_fractal_is_rejected() {
        // Last bar is a "candidate low" but only one bar to its right exists,
        // so it cannot be confirmed with leftRight=2.
        List<Candle> candles = candles(new double[][]{
                {100, 101, 99,  100},
                {99,  100, 98,  99},
                {98,  99,  95,  96},   // candidate
                {97,  99,  96,  98}    // only 1 bar to the right
        });
        Optional<Fractal> low = WilliamsFractal.findMostRecentConfirmedLow(candles, 2, 10);
        assertTrue(low.isEmpty(), "should not return an unconfirmed pivot");
    }

    @Test
    void ties_are_not_fractals() {
        List<Candle> candles = candles(new double[][]{
                {100, 101, 99,  100},
                {99,  100, 95,  96},   // candidate
                {99,  100, 95,  97},   // tie at low=95 → not strictly lowest
                {99,  100, 95,  96},
                {100, 101, 96,  98},
                {101, 102, 97,  99}
        });
        Optional<Fractal> low = WilliamsFractal.findMostRecentConfirmedLow(candles, 2, 10);
        assertTrue(low.isEmpty(), "tied lows must not count as a fractal");
    }

    @Test
    void as_of_index_bounds_pivot_search() {
        List<Candle> candles = candles(new double[][]{
                {100, 101, 99,  100},
                {99,  100, 98,  99},
                {98,  99,  95,  96.5},
                {97,  99,  96,  98},
                {98,  101, 97,  100},
                {99,  102, 98,  101}
        });
        // Same pivot at index 2 must be reachable when asOfIndex>=4
        assertTrue(WilliamsFractal.findMostRecentConfirmedLow(candles, 4, 2, 10).isPresent());
        // But if asOfIndex=3, the pivot at 2 has only 1 confirming bar to its right.
        assertTrue(WilliamsFractal.findMostRecentConfirmedLow(candles, 3, 2, 10).isEmpty());
    }

    private static List<Candle> candles(double[][] ohlc) {
        List<Candle> out = new ArrayList<>(ohlc.length);
        Instant ts = Instant.parse("2025-01-02T14:30:00Z");
        for (int i = 0; i < ohlc.length; i++) {
            double[] r = ohlc[i];
            out.add(new Candle(
                    Instrument.MNQ, "5m",
                    ts.plus(i * 5L, ChronoUnit.MINUTES),
                    BigDecimal.valueOf(r[0]),
                    BigDecimal.valueOf(r[1]),
                    BigDecimal.valueOf(r[2]),
                    BigDecimal.valueOf(r[3]),
                    1000L));
        }
        return out;
    }
}
