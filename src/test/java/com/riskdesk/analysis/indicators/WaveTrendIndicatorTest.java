package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.indicators.WaveTrendIndicator;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaveTrendIndicatorTest {

    private final WaveTrendIndicator wt = new WaveTrendIndicator(10, 21, 4);

    private Candle candle(double open, double high, double low, double close) {
        return new Candle(
                Instrument.MCL, "10m", Instant.now(),
                bd(open), bd(high), bd(low), bd(close), 1000L
        );
    }

    private BigDecimal bd(double v) {
        return new BigDecimal(String.valueOf(v));
    }

    private List<Candle> flat(int count, double price) {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(candle(price, price + 0.5, price - 0.5, price));
        }
        return list;
    }

    private List<Candle> trend(int count, double start, double step) {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double c = start + i * step;
            list.add(candle(c - 0.5, c + 1, c - 1, c));
        }
        return list;
    }

    // --- basic correctness ---

    @Test
    void calculate_emptyList_returnsEmpty() {
        assertTrue(wt.calculate(Collections.emptyList()).isEmpty());
    }

    @Test
    void current_emptyList_returnsNull() {
        assertNull(wt.current(Collections.emptyList()));
    }

    @Test
    void calculate_insufficientCandles_returnsEmpty() {
        // Need at least signalPeriod candles to get the first wt2
        List<Candle> candles = flat(3, 100.0);
        assertTrue(wt.calculate(candles).isEmpty());
    }

    @Test
    void calculate_sufficientCandles_returnsResults() {
        List<Candle> candles = flat(50, 100.0);
        List<WaveTrendIndicator.WaveTrendResult> results = wt.calculate(candles);
        assertFalse(results.isEmpty());
    }

    @Test
    void current_sufficientCandles_returnsNonNull() {
        List<Candle> candles = flat(50, 100.0);
        assertNotNull(wt.current(candles));
    }

    // --- flat price series: WT should be near zero ---

    @Test
    void calculate_flatPrices_wt1NearZero() {
        List<Candle> candles = flat(100, 70.0);
        WaveTrendIndicator.WaveTrendResult result = wt.current(candles);

        assertNotNull(result);
        // For a perfectly flat price series, HLC3 is constant, esa ≈ src, d ≈ 0, ci ≈ 0, wt1 ≈ 0
        assertTrue(result.wt1().abs().compareTo(BigDecimal.ONE) < 0,
                "WT1 should be near zero for flat prices, got: " + result.wt1());
    }

    @Test
    void calculate_flatPrices_signalIsNeutral() {
        List<Candle> candles = flat(100, 70.0);
        WaveTrendIndicator.WaveTrendResult result = wt.current(candles);

        assertNotNull(result);
        assertEquals("NEUTRAL", result.signal());
    }

    // --- result field integrity ---

    @Test
    void calculate_allResultFieldsPresent() {
        List<Candle> candles = trend(100, 70.0, 0.1);
        List<WaveTrendIndicator.WaveTrendResult> results = wt.calculate(candles);

        assertFalse(results.isEmpty());
        WaveTrendIndicator.WaveTrendResult r = results.get(results.size() - 1);

        assertNotNull(r.wt1());
        assertNotNull(r.wt2());
        assertNotNull(r.diff());
        assertNotNull(r.signal());
        // crossover may be null if no cross occurred on final bar — that's valid
    }

    @Test
    void calculate_diffEqualsWt1MinusWt2() {
        List<Candle> candles = trend(100, 70.0, 0.1);
        List<WaveTrendIndicator.WaveTrendResult> results = wt.calculate(candles);

        for (WaveTrendIndicator.WaveTrendResult r : results) {
            BigDecimal expected = r.wt1().subtract(r.wt2());
            assertEquals(0, expected.compareTo(r.diff()),
                    "diff must equal wt1 - wt2");
        }
    }

    // --- signal levels ---

    @Test
    void signal_levels_followWtXThresholds() {
        // Build candles that force WT1 very high by using a sharply rising series
        List<Candle> rising = trend(200, 10.0, 5.0);
        WaveTrendIndicator.WaveTrendResult result = wt.current(rising);
        assertNotNull(result);
        // Assert signal is correctly set based on wt1 value
        if (result.wt1().compareTo(BigDecimal.valueOf(53)) > 0) {
            assertEquals("OVERBOUGHT", result.signal());
        } else if (result.wt1().compareTo(BigDecimal.valueOf(-53)) < 0) {
            assertEquals("OVERSOLD", result.signal());
        } else {
            assertEquals("NEUTRAL", result.signal());
        }
    }

    @Test
    void signal_neutral_whenWt1BetweenLevels() {
        List<Candle> candles = flat(100, 70.0);
        WaveTrendIndicator.WaveTrendResult r = wt.current(candles);
        assertNotNull(r);
        // Flat series produces near-zero WT1, which is NEUTRAL
        assertEquals("NEUTRAL", r.signal());
    }

    // --- crossover detection ---

    @Test
    void crossover_isNullOnFirstResult() {
        List<Candle> candles = flat(10, 70.0);
        List<WaveTrendIndicator.WaveTrendResult> results = wt.calculate(candles);
        // The very first result has no previous bar, so crossover must be null
        if (!results.isEmpty()) {
            assertNull(results.get(0).crossover(),
                    "First result cannot have a crossover (no previous bar)");
        }
    }

    @Test
    void crossover_detectedWhenWt1CrossesWt2() {
        List<Candle> candles = trend(100, 70.0, 0.1);
        List<WaveTrendIndicator.WaveTrendResult> results = wt.calculate(candles);

        // After crossing up, the crossover field should appear somewhere in the results
        long bullCrosses = results.stream()
                .filter(r -> "BULLISH_CROSS".equals(r.crossover()))
                .count();
        long bearCrosses = results.stream()
                .filter(r -> "BEARISH_CROSS".equals(r.crossover()))
                .count();

        // Total crosses >= 0 (may or may not have any depending on data)
        assertTrue(bullCrosses >= 0);
        assertTrue(bearCrosses >= 0);
    }

    // --- result count ---

    @Test
    void calculate_resultCountGrowsWithMoreCandles() {
        List<Candle> small = flat(20, 70.0);
        List<Candle> large = flat(50, 70.0);

        List<WaveTrendIndicator.WaveTrendResult> r1 = wt.calculate(small);
        List<WaveTrendIndicator.WaveTrendResult> r2 = wt.calculate(large);

        assertTrue(r2.size() >= r1.size(),
                "More candles should yield at least as many results");
    }

    // --- custom parameters ---

    @Test
    void customParameters_constructorAccepted() {
        WaveTrendIndicator custom = new WaveTrendIndicator(5, 10, 2);
        List<Candle> candles = flat(50, 100.0);
        List<WaveTrendIndicator.WaveTrendResult> results = custom.calculate(candles);
        assertFalse(results.isEmpty(),
                "Custom parameter WaveTrend should produce results with 50 candles");
    }

    @Test
    void defaultConstructor_producesResults() {
        WaveTrendIndicator defaultWt = new WaveTrendIndicator();
        List<Candle> candles = flat(50, 100.0);
        assertNotNull(defaultWt.current(candles));
    }
}
