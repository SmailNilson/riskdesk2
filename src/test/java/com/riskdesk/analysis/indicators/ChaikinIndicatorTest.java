package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.indicators.ChaikinIndicator;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChaikinIndicatorTest {

    private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
    private ChaikinIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new ChaikinIndicator(); // defaults: fast=3, slow=10, cmf=20
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private Candle candle(double open, double high, double low, double close, long volume, Instant time) {
        return new Candle(Instrument.MCL, "10m", time,
                new BigDecimal(String.valueOf(open)), new BigDecimal(String.valueOf(high)),
                new BigDecimal(String.valueOf(low)), new BigDecimal(String.valueOf(close)), volume);
    }

    private List<Candle> generateCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        double base = 70.0;
        for (int i = 0; i < count; i++) {
            double open = base + i * 0.1;
            double close = open + 0.15;
            double high = Math.max(open, close) + 0.10;
            double low = Math.min(open, close) - 0.10;
            long volume = 1000 + i * 50;
            candles.add(candle(open, high, low, close, volume, BASE_TIME.plusSeconds(600L * i)));
        }
        return candles;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void compute_with30Candles_returnsNonNullOscillatorAndCMF() {
        List<Candle> candles = generateCandles(30);

        List<BigDecimal> oscillator = indicator.calculateOscillator(candles);
        List<BigDecimal> cmf = indicator.calculateCMF(candles);

        assertNotNull(oscillator);
        assertFalse(oscillator.isEmpty(), "Oscillator should not be empty for 30 candles");
        assertNotNull(cmf);
        assertFalse(cmf.isEmpty(), "CMF should not be empty for 30 candles");

        // Verify every value is non-null
        for (BigDecimal val : oscillator) {
            assertNotNull(val, "Each oscillator value should be non-null");
        }
        for (BigDecimal val : cmf) {
            assertNotNull(val, "Each CMF value should be non-null");
        }
    }

    @Test
    void cmfValues_areBetweenNegativeOneAndPositiveOne() {
        List<Candle> candles = generateCandles(30);

        List<BigDecimal> cmf = indicator.calculateCMF(candles);
        assertFalse(cmf.isEmpty());

        for (BigDecimal val : cmf) {
            assertTrue(val.doubleValue() >= -1.0,
                    "CMF value should be >= -1, was: " + val);
            assertTrue(val.doubleValue() <= 1.0,
                    "CMF value should be <= 1, was: " + val);
        }
    }

    @Test
    void bullishCandlePattern_producesPositiveADContribution() {
        // Candle where close is near high with high volume -> positive MFV -> positive A/D
        List<Candle> candles = new ArrayList<>();
        // Single strong bullish candle: close near high
        candles.add(candle(70.00, 71.00, 69.50, 70.95, 5000, BASE_TIME));

        List<BigDecimal> adLine = indicator.adLine(candles);

        assertNotNull(adLine);
        assertEquals(1, adLine.size());
        assertTrue(adLine.get(0).doubleValue() > 0,
                "A/D should be positive for bullish candle near high, was: " + adLine.get(0));
    }

    @Test
    void insufficientData_returnsEmptyOscillator() {
        // Oscillator needs slowPeriod=10 candles minimum
        List<Candle> candles = generateCandles(5);

        List<BigDecimal> oscillator = indicator.calculateOscillator(candles);

        assertNotNull(oscillator);
        assertTrue(oscillator.isEmpty(),
                "Oscillator should be empty for insufficient data (5 < 10)");
    }

    @Test
    void insufficientData_returnsEmptyCMF() {
        // CMF needs cmfPeriod=20 candles minimum
        List<Candle> candles = generateCandles(10);

        List<BigDecimal> cmf = indicator.calculateCMF(candles);

        assertNotNull(cmf);
        assertTrue(cmf.isEmpty(),
                "CMF should be empty for insufficient data (10 < 20)");
    }

    @Test
    void dojiCandle_doesNotCrash() {
        // Doji: high == low (division by zero protection)
        List<Candle> candles = new ArrayList<>();
        candles.add(candle(70.00, 70.00, 70.00, 70.00, 1000, BASE_TIME));

        assertDoesNotThrow(() -> {
            List<BigDecimal> adLine = indicator.adLine(candles);
            assertNotNull(adLine);
            assertEquals(1, adLine.size());
            // MFV should be 0 for doji -> A/D contribution is 0
            assertEquals(0, adLine.get(0).compareTo(BigDecimal.ZERO),
                    "A/D for doji should be 0");
        });
    }

    @Test
    void adLine_cumulativelyAccumulates() {
        // Two bullish candles: A/D should increase cumulatively
        List<Candle> candles = new ArrayList<>();
        candles.add(candle(70.00, 71.00, 69.50, 70.90, 1000, BASE_TIME));
        candles.add(candle(71.00, 72.00, 70.50, 71.90, 1000, BASE_TIME.plusSeconds(600)));

        List<BigDecimal> adLine = indicator.adLine(candles);

        assertEquals(2, adLine.size());
        assertTrue(adLine.get(0).doubleValue() > 0, "First A/D should be positive");
        assertTrue(adLine.get(1).doubleValue() > adLine.get(0).doubleValue(),
                "Second A/D should be greater than first (cumulative)");
    }
}
