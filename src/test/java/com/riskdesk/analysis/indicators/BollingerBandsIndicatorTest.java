package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.indicators.BollingerBandsIndicator;
import com.riskdesk.domain.engine.indicators.BollingerBandsIndicator.BBResult;
import com.riskdesk.domain.engine.indicators.BollingerBandsIndicator.BBTrendResult;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BollingerBandsIndicatorTest {

    private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
    private BollingerBandsIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new BollingerBandsIndicator(); // defaults: period=20, stddev=2.0, trendFast=14, trendSlow=30, trendFactor=2.0
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
            double close = open + 0.05 + (i % 3 == 0 ? 0.2 : -0.05);
            double high = Math.max(open, close) + 0.15;
            double low = Math.min(open, close) - 0.15;
            long volume = 1000 + i * 50;
            candles.add(candle(open, high, low, close, volume, BASE_TIME.plusSeconds(600L * i)));
        }
        return candles;
    }

    private List<Candle> generateConstantPriceCandles(int count, double price) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candles.add(candle(price, price, price, price, 1000, BASE_TIME.plusSeconds(600L * i)));
        }
        return candles;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void compute_with30Candles_returnsBBMiddleUpperLower() {
        List<Candle> candles = generateCandles(30);

        List<BBResult> results = indicator.calculate(candles);

        assertNotNull(results);
        assertFalse(results.isEmpty(), "BB results should not be empty for 30 candles");

        BBResult last = results.get(results.size() - 1);
        assertNotNull(last.middle(), "Middle band should not be null");
        assertNotNull(last.upper(), "Upper band should not be null");
        assertNotNull(last.lower(), "Lower band should not be null");
        assertNotNull(last.width(), "Width should not be null");
        assertNotNull(last.pct(), "Percent B should not be null");
    }

    @Test
    void upperBand_greaterThanMiddle_greaterThanLower() {
        List<Candle> candles = generateCandles(30);

        List<BBResult> results = indicator.calculate(candles);
        assertFalse(results.isEmpty());

        for (BBResult bb : results) {
            assertTrue(bb.upper().compareTo(bb.middle()) >= 0,
                    "Upper should be >= Middle. Upper=" + bb.upper() + ", Middle=" + bb.middle());
            assertTrue(bb.middle().compareTo(bb.lower()) >= 0,
                    "Middle should be >= Lower. Middle=" + bb.middle() + ", Lower=" + bb.lower());
        }
    }

    @Test
    void width_equalsUpperMinusLower() {
        List<Candle> candles = generateCandles(30);

        List<BBResult> results = indicator.calculate(candles);
        assertFalse(results.isEmpty());

        for (BBResult bb : results) {
            BigDecimal expectedWidth = bb.upper().subtract(bb.lower());
            assertEquals(0, expectedWidth.compareTo(bb.width()),
                    "Width should equal upper - lower. Expected=" + expectedWidth + ", Got=" + bb.width());
        }
    }

    @Test
    void percentB_forCloseAtUpperBand_isNearOne() {
        // Build candles where the last close is at or near the upper band
        // We use 25 normal candles followed by 5 candles with a big price spike
        List<Candle> candles = new ArrayList<>();
        double base = 70.0;
        for (int i = 0; i < 20; i++) {
            double price = base + 0.01 * i;
            candles.add(candle(price, price + 0.1, price - 0.1, price, 1000,
                    BASE_TIME.plusSeconds(600L * i)));
        }
        // spike the last candle so close > upper band
        double spikePrice = base + 5.0;
        candles.add(candle(spikePrice - 0.5, spikePrice + 0.1, spikePrice - 0.6, spikePrice, 2000,
                BASE_TIME.plusSeconds(600L * 20)));

        List<BBResult> results = indicator.calculate(candles);
        assertFalse(results.isEmpty());

        BBResult last = results.get(results.size() - 1);
        // %B should be >= 1.0 when close is at or above upper band
        assertTrue(last.pct().doubleValue() >= 0.9,
                "%B should be near or above 1.0 when close is at upper band, was: " + last.pct());
    }

    @Test
    void percentB_forCloseAtLowerBand_isNearZero() {
        // Normal candles followed by a big drop
        List<Candle> candles = new ArrayList<>();
        double base = 70.0;
        for (int i = 0; i < 20; i++) {
            double price = base + 0.01 * i;
            candles.add(candle(price, price + 0.1, price - 0.1, price, 1000,
                    BASE_TIME.plusSeconds(600L * i)));
        }
        // drop the last candle so close < lower band
        double dropPrice = base - 5.0;
        candles.add(candle(dropPrice + 0.5, dropPrice + 0.6, dropPrice - 0.1, dropPrice, 2000,
                BASE_TIME.plusSeconds(600L * 20)));

        List<BBResult> results = indicator.calculate(candles);
        assertFalse(results.isEmpty());

        BBResult last = results.get(results.size() - 1);
        // %B should be <= 0.0 when close is at or below lower band
        assertTrue(last.pct().doubleValue() <= 0.1,
                "%B should be near or below 0.0 when close is at lower band, was: " + last.pct());
    }

    @Test
    void constantPriceCandles_allBandsEqual_zeroStddev() {
        List<Candle> candles = generateConstantPriceCandles(25, 70.0);

        List<BBResult> results = indicator.calculate(candles);
        assertFalse(results.isEmpty());

        for (BBResult bb : results) {
            assertEquals(0, bb.upper().compareTo(bb.middle()),
                    "Upper should equal Middle for constant price");
            assertEquals(0, bb.middle().compareTo(bb.lower()),
                    "Middle should equal Lower for constant price");
            assertEquals(0, bb.width().compareTo(BigDecimal.ZERO),
                    "Width should be zero for constant price");
        }
    }

    @Test
    void bbTrend_with35Candles_returnsNonNullResult() {
        // BBTrend needs trendSlowPeriod=30 candles minimum
        List<Candle> candles = generateCandles(35);

        List<BBTrendResult> trends = indicator.calculateTrend(candles);

        assertNotNull(trends);
        assertFalse(trends.isEmpty(), "BBTrend results should not be empty for 35 candles");

        for (BBTrendResult t : trends) {
            assertNotNull(t.value(), "Trend value should not be null");
            assertNotNull(t.signal(), "Trend signal should not be null");
            assertTrue(t.signal().equals("TRENDING") || t.signal().equals("CONSOLIDATING"),
                    "Signal should be TRENDING or CONSOLIDATING, was: " + t.signal());
        }
    }

    @Test
    void insufficientData_returnsEmpty() {
        // BB needs period=20 candles minimum
        List<Candle> candles = generateCandles(10);

        List<BBResult> results = indicator.calculate(candles);

        assertNotNull(results);
        assertTrue(results.isEmpty(),
                "BB should return empty for insufficient data (10 < 20)");
    }

    @Test
    void insufficientData_trendReturnsEmpty() {
        // BBTrend needs trendSlowPeriod=30 candles minimum
        List<Candle> candles = generateCandles(20);

        List<BBTrendResult> trends = indicator.calculateTrend(candles);

        assertNotNull(trends);
        assertTrue(trends.isEmpty(),
                "BBTrend should return empty for insufficient data (20 < 30)");
    }

    @Test
    void currentBB_returnsLastResult() {
        List<Candle> candles = generateCandles(30);

        BBResult current = indicator.current(candles);
        List<BBResult> all = indicator.calculate(candles);

        assertNotNull(current);
        assertFalse(all.isEmpty());
        assertEquals(0, current.middle().compareTo(all.get(all.size() - 1).middle()),
                "current() should return the last element of calculate()");
    }
}
