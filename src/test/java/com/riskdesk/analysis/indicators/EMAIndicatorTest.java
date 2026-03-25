package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.indicators.EMAIndicator;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EMAIndicatorTest {

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
     * Build a list of candles where the close price starts at {@code startClose}
     * and increments by {@code step} for each subsequent candle.
     */
    private List<Candle> buildCandles(int count, double startClose, double step) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = startClose + i * step;
            double open = close - 0.5;
            double high = close + 1.0;
            double low = close - 1.0;
            candles.add(candle(open, high, low, close, 1000 + i * 10));
        }
        return candles;
    }

    /**
     * Build candles all with the same close price.
     */
    private List<Candle> buildFlatCandles(int count, double price) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            candles.add(candle(price, price + 0.5, price - 0.5, price, 1000));
        }
        return candles;
    }

    // --------------- tests ---------------

    @Test
    void calculate_with20Candles_ema9AndEma50_areReasonable() {
        List<Candle> candles = buildCandles(20, 70.0, 0.5);

        EMAIndicator ema9 = new EMAIndicator(9);
        EMAIndicator ema50 = new EMAIndicator(50);

        List<BigDecimal> ema9Values = ema9.calculate(candles);
        List<BigDecimal> ema50Values = ema50.calculate(candles);

        // EMA-9 should produce results (20 >= 9)
        assertFalse(ema9Values.isEmpty(), "EMA-9 should produce results with 20 candles");
        for (BigDecimal v : ema9Values) {
            assertNotNull(v);
            assertTrue(v.compareTo(BigDecimal.ZERO) > 0, "EMA value should be positive");
        }

        // EMA-50 should produce no results (20 < 50)
        assertTrue(ema50Values.isEmpty(), "EMA-50 should not produce results with only 20 candles");

        // The latest EMA-9 value should be closer to the most recent close than the first EMA-9 value
        BigDecimal recentClose = candles.get(candles.size() - 1).getClose();
        BigDecimal latestEma9 = ema9Values.get(ema9Values.size() - 1);
        BigDecimal firstEma9 = ema9Values.get(0);

        BigDecimal distLatest = recentClose.subtract(latestEma9).abs();
        BigDecimal distFirst = recentClose.subtract(firstEma9).abs();
        assertTrue(distLatest.compareTo(distFirst) < 0,
                "Latest EMA-9 should be closer to recent price than the first EMA-9 value");
    }

    @Test
    void calculate_withInsufficientData_returnsEmptyList() {
        EMAIndicator ema9 = new EMAIndicator(9);
        List<Candle> candles = buildCandles(5, 70.0, 0.5);

        List<BigDecimal> result = ema9.calculate(candles);

        assertTrue(result.isEmpty(), "EMA with fewer candles than period should return empty list");
    }

    @Test
    void current_withInsufficientData_returnsNull() {
        EMAIndicator ema9 = new EMAIndicator(9);
        List<Candle> candles = buildCandles(5, 70.0, 0.5);

        BigDecimal result = ema9.current(candles);

        assertNull(result, "current() should return null when data is insufficient");
    }

    @Test
    void detectCrossover_goldenCross_whenFastCrossesAboveSlow() {
        // Construct two EMA series where the fast crosses above the slow at the last element.
        // prev: fast <= slow  -->  curr: fast > slow
        List<BigDecimal> fastEma = List.of(
                new BigDecimal("70.00"),
                new BigDecimal("71.00"),
                new BigDecimal("72.00"),  // prev: fast(72) <= slow(73)
                new BigDecimal("75.00")   // curr: fast(75) > slow(74)
        );
        List<BigDecimal> slowEma = List.of(
                new BigDecimal("71.00"),
                new BigDecimal("72.00"),
                new BigDecimal("73.00"),  // prev slow
                new BigDecimal("74.00")   // curr slow
        );

        String crossover = EMAIndicator.detectCrossover(fastEma, slowEma);

        assertEquals("GOLDEN_CROSS", crossover);
    }

    @Test
    void detectCrossover_deathCross_whenFastCrossesBelowSlow() {
        // prev: fast >= slow  -->  curr: fast < slow
        List<BigDecimal> fastEma = List.of(
                new BigDecimal("75.00"),
                new BigDecimal("74.00"),
                new BigDecimal("73.00"),  // prev: fast(73) >= slow(72)
                new BigDecimal("71.00")   // curr: fast(71) < slow(72.5)
        );
        List<BigDecimal> slowEma = List.of(
                new BigDecimal("74.00"),
                new BigDecimal("73.00"),
                new BigDecimal("72.00"),  // prev slow
                new BigDecimal("72.50")   // curr slow
        );

        String crossover = EMAIndicator.detectCrossover(fastEma, slowEma);

        assertEquals("DEATH_CROSS", crossover);
    }

    @Test
    void detectCrossover_noCross_returnsNull() {
        // fast stays above slow the whole time
        List<BigDecimal> fastEma = List.of(
                new BigDecimal("75.00"),
                new BigDecimal("76.00"),
                new BigDecimal("77.00")
        );
        List<BigDecimal> slowEma = List.of(
                new BigDecimal("70.00"),
                new BigDecimal("71.00"),
                new BigDecimal("72.00")
        );

        String crossover = EMAIndicator.detectCrossover(fastEma, slowEma);

        assertNull(crossover, "No crossover should return null");
    }

    @Test
    void detectCrossover_insufficientData_returnsNull() {
        List<BigDecimal> fastEma = List.of(new BigDecimal("75.00"));
        List<BigDecimal> slowEma = List.of(new BigDecimal("70.00"));

        String crossover = EMAIndicator.detectCrossover(fastEma, slowEma);

        assertNull(crossover, "Less than 2 EMA values should return null");
    }

    @Test
    void calculate_allCandlesSameClose_emaEqualsThatPrice() {
        double price = 72.50;
        List<Candle> candles = buildFlatCandles(20, price);

        EMAIndicator ema9 = new EMAIndicator(9);
        List<BigDecimal> values = ema9.calculate(candles);

        assertFalse(values.isEmpty());

        BigDecimal expected = new BigDecimal(String.valueOf(price));
        for (BigDecimal v : values) {
            // EMA of a constant series should equal the constant
            assertEquals(0, v.compareTo(expected),
                    "EMA of constant close prices should equal that price, but got " + v);
        }
    }

    @Test
    void calculate_resultCount_isCorrect() {
        List<Candle> candles = buildCandles(20, 70.0, 0.5);
        EMAIndicator ema9 = new EMAIndicator(9);

        List<BigDecimal> values = ema9.calculate(candles);

        // First result is the SMA seed (uses candles 0..8), then one per remaining candle (9..19)
        // Total = 1 + (20 - 9) = 12
        assertEquals(20 - 9 + 1, values.size(),
                "Result count should be candles.size() - period + 1");
    }

    @Test
    void current_returnsMostRecentEmaValue() {
        List<Candle> candles = buildCandles(20, 70.0, 0.5);
        EMAIndicator ema9 = new EMAIndicator(9);

        BigDecimal current = ema9.current(candles);
        List<BigDecimal> all = ema9.calculate(candles);

        assertNotNull(current);
        assertEquals(all.get(all.size() - 1), current,
                "current() should return the last element of calculate()");
    }
}
