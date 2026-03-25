package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.indicators.MACDIndicator;
import com.riskdesk.domain.engine.indicators.MACDIndicator.MACDResult;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MACDIndicatorTest {

    // --------------- helper ---------------

    private Candle candle(double open, double high, double low, double close, long volume) {
        return new Candle(
                Instrument.MCL, "10m", Instant.now(),
                new BigDecimal(String.valueOf(open)),
                new BigDecimal(String.valueOf(high)),
                new BigDecimal(String.valueOf(low)),
                new BigDecimal(String.valueOf(close)),
                volume
        );
    }

    /**
     * Build candles with linearly increasing close prices (uptrend).
     */
    private List<Candle> buildUptrendCandles(int count, double startClose, double step) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = startClose + i * step;
            double open = close - 0.3;
            double high = close + 1.0;
            double low = close - 1.0;
            candles.add(candle(open, high, low, close, 1000));
        }
        return candles;
    }

    /**
     * Build candles with linearly decreasing close prices (downtrend).
     */
    private List<Candle> buildDowntrendCandles(int count, double startClose, double step) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = startClose - i * step;
            double open = close + 0.3;
            double high = close + 1.0;
            double low = close - 1.0;
            candles.add(candle(open, high, low, close, 1000));
        }
        return candles;
    }

    // --------------- tests ---------------

    @Test
    void calculate_with50Candles_allFieldsNonNull() {
        MACDIndicator macd = new MACDIndicator(); // (12, 26, 9)
        List<Candle> candles = buildUptrendCandles(50, 60.0, 0.5);

        List<MACDResult> results = macd.calculate(candles);

        assertFalse(results.isEmpty(), "MACD should produce results with 50 candles");
        for (MACDResult r : results) {
            assertNotNull(r.macdLine(), "MACD line should not be null");
            assertNotNull(r.signalLine(), "Signal line should not be null");
            assertNotNull(r.histogram(), "Histogram should not be null");
        }
    }

    @Test
    void calculate_histogramEquals_macdMinusSignal() {
        MACDIndicator macd = new MACDIndicator();
        List<Candle> candles = buildUptrendCandles(50, 60.0, 0.5);

        List<MACDResult> results = macd.calculate(candles);

        assertFalse(results.isEmpty());
        for (MACDResult r : results) {
            BigDecimal expected = r.macdLine().subtract(r.signalLine())
                    .setScale(5, RoundingMode.HALF_UP);
            assertEquals(0, r.histogram().compareTo(expected),
                    "Histogram should equal MACD - Signal. Expected " + expected + " but got " + r.histogram());
        }
    }

    @Test
    void calculate_withUptrendData_macdLinePositive() {
        MACDIndicator macd = new MACDIndicator();
        // Strong uptrend: fast EMA should be above slow EMA -> MACD line positive
        List<Candle> candles = buildUptrendCandles(60, 50.0, 1.0);

        List<MACDResult> results = macd.calculate(candles);

        assertFalse(results.isEmpty());

        // After enough data for the trend to establish, MACD line should be positive
        MACDResult last = results.get(results.size() - 1);
        assertTrue(last.macdLine().compareTo(BigDecimal.ZERO) > 0,
                "MACD line should be positive in a strong uptrend, got " + last.macdLine());
    }

    @Test
    void detectCrossover_bullishCross() {
        // Create a scenario: histogram goes from <= 0 to > 0
        List<MACDResult> results = List.of(
                new MACDResult(new BigDecimal("0.50"), new BigDecimal("0.60"), new BigDecimal("-0.10")),
                new MACDResult(new BigDecimal("0.70"), new BigDecimal("0.60"), new BigDecimal("0.10"))
        );

        MACDIndicator macd = new MACDIndicator();
        String crossover = macd.detectCrossover(results);

        assertEquals("BULLISH_CROSS", crossover);
    }

    @Test
    void detectCrossover_bearishCross() {
        // histogram goes from >= 0 to < 0
        List<MACDResult> results = List.of(
                new MACDResult(new BigDecimal("0.70"), new BigDecimal("0.60"), new BigDecimal("0.10")),
                new MACDResult(new BigDecimal("0.50"), new BigDecimal("0.60"), new BigDecimal("-0.10"))
        );

        MACDIndicator macd = new MACDIndicator();
        String crossover = macd.detectCrossover(results);

        assertEquals("BEARISH_CROSS", crossover);
    }

    @Test
    void detectCrossover_noCross_returnsNull() {
        // histogram stays positive
        List<MACDResult> results = List.of(
                new MACDResult(new BigDecimal("0.80"), new BigDecimal("0.60"), new BigDecimal("0.20")),
                new MACDResult(new BigDecimal("0.90"), new BigDecimal("0.60"), new BigDecimal("0.30"))
        );

        MACDIndicator macd = new MACDIndicator();
        String crossover = macd.detectCrossover(results);

        assertNull(crossover, "No crossover should return null");
    }

    @Test
    void detectCrossover_insufficientResults_returnsNull() {
        List<MACDResult> results = List.of(
                new MACDResult(new BigDecimal("0.50"), new BigDecimal("0.40"), new BigDecimal("0.10"))
        );

        MACDIndicator macd = new MACDIndicator();
        String crossover = macd.detectCrossover(results);

        assertNull(crossover, "Single result should return null for crossover detection");
    }

    @Test
    void calculate_insufficientData_returnsEmpty() {
        MACDIndicator macd = new MACDIndicator(); // needs at least 26 + 9 - 1 = 34 candles
        // With fewer than 26 candles, slow EMA produces nothing
        List<Candle> candles = buildUptrendCandles(20, 60.0, 0.5);

        List<MACDResult> results = macd.calculate(candles);

        assertTrue(results.isEmpty(),
                "MACD with fewer than 26 candles should return empty list");
    }

    @Test
    void current_returnsLastResult() {
        MACDIndicator macd = new MACDIndicator();
        List<Candle> candles = buildUptrendCandles(50, 60.0, 0.5);

        MACDResult current = macd.current(candles);
        List<MACDResult> all = macd.calculate(candles);

        assertNotNull(current);
        assertEquals(all.get(all.size() - 1).macdLine(), current.macdLine());
        assertEquals(all.get(all.size() - 1).signalLine(), current.signalLine());
        assertEquals(all.get(all.size() - 1).histogram(), current.histogram());
    }

    @Test
    void current_insufficientData_returnsNull() {
        MACDIndicator macd = new MACDIndicator();
        List<Candle> candles = buildUptrendCandles(10, 60.0, 0.5);

        MACDResult current = macd.current(candles);

        assertNull(current, "current() should return null for insufficient data");
    }

    @Test
    void calculate_withDowntrendData_macdLineNegative() {
        MACDIndicator macd = new MACDIndicator();
        List<Candle> candles = buildDowntrendCandles(60, 100.0, 1.0);

        List<MACDResult> results = macd.calculate(candles);

        assertFalse(results.isEmpty());

        MACDResult last = results.get(results.size() - 1);
        assertTrue(last.macdLine().compareTo(BigDecimal.ZERO) < 0,
                "MACD line should be negative in a strong downtrend, got " + last.macdLine());
    }
}
