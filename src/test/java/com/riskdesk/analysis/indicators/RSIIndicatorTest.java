package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.indicators.RSIIndicator;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RSIIndicatorTest {

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
     * Build candles with alternating up/down close prices to simulate mixed movement.
     */
    private List<Candle> buildMixedCandles(int count, double startClose) {
        List<Candle> candles = new ArrayList<>();
        double close = startClose;
        for (int i = 0; i < count; i++) {
            double open = close - 0.3;
            double high = close + 1.0;
            double low = close - 1.0;
            candles.add(candle(open, high, low, close, 1000));
            // Alternate: up 0.8, down 0.4 -> net upward bias but with losses
            close = (i % 2 == 0) ? close + 0.8 : close - 0.4;
        }
        return candles;
    }

    /**
     * Build monotonically increasing candles.
     */
    private List<Candle> buildIncreasingCandles(int count, double startClose, double step) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = startClose + i * step;
            double open = close - 0.5;
            double high = close + 1.0;
            double low = close - 1.0;
            candles.add(candle(open, high, low, close, 1000));
        }
        return candles;
    }

    /**
     * Build monotonically decreasing candles.
     */
    private List<Candle> buildDecreasingCandles(int count, double startClose, double step) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = startClose - i * step;
            double open = close + 0.5;
            double high = close + 1.0;
            double low = close - 1.0;
            candles.add(candle(open, high, low, close, 1000));
        }
        return candles;
    }

    // --------------- tests ---------------

    @Test
    void calculate_with30MixedCandles_rsiBetween0And100() {
        RSIIndicator rsi = new RSIIndicator();
        List<Candle> candles = buildMixedCandles(30, 70.0);

        List<BigDecimal> values = rsi.calculate(candles);

        assertFalse(values.isEmpty(), "RSI should produce results with 30 candles (period=14)");
        for (BigDecimal v : values) {
            assertNotNull(v);
            assertTrue(v.compareTo(BigDecimal.ZERO) >= 0, "RSI must be >= 0, got " + v);
            assertTrue(v.compareTo(new BigDecimal("100")) <= 0, "RSI must be <= 100, got " + v);
        }
    }

    @Test
    void calculate_allGains_rsiApproaches100() {
        RSIIndicator rsi = new RSIIndicator();
        List<Candle> candles = buildIncreasingCandles(30, 60.0, 1.0);

        List<BigDecimal> values = rsi.calculate(candles);

        assertFalse(values.isEmpty());

        // All price changes are gains -> avgLoss = 0 -> RSI = 100
        BigDecimal lastRsi = values.get(values.size() - 1);
        assertTrue(lastRsi.compareTo(new BigDecimal("95")) > 0,
                "All-gain RSI should approach 100, got " + lastRsi);
    }

    @Test
    void calculate_allLosses_rsiApproaches0() {
        RSIIndicator rsi = new RSIIndicator();
        List<Candle> candles = buildDecreasingCandles(30, 90.0, 1.0);

        List<BigDecimal> values = rsi.calculate(candles);

        assertFalse(values.isEmpty());

        BigDecimal lastRsi = values.get(values.size() - 1);
        assertTrue(lastRsi.compareTo(new BigDecimal("5")) < 0,
                "All-loss RSI should approach 0, got " + lastRsi);
    }

    @Test
    void calculate_insufficientData_returnsEmpty() {
        RSIIndicator rsi = new RSIIndicator(); // period = 14
        // Need > period candles. With 14 candles (size <= period), returns empty.
        List<Candle> candles = buildMixedCandles(14, 70.0);

        List<BigDecimal> values = rsi.calculate(candles);

        assertTrue(values.isEmpty(),
                "RSI with exactly 'period' candles should return empty (needs > period)");
    }

    @Test
    void current_insufficientData_returnsNull() {
        RSIIndicator rsi = new RSIIndicator();
        List<Candle> candles = buildMixedCandles(10, 70.0);

        BigDecimal result = rsi.current(candles);

        assertNull(result, "current() should return null for insufficient data");
    }

    @Test
    void signal_oversold_whenRsiBelowOversoldThreshold() {
        RSIIndicator rsi = new RSIIndicator(); // oversold = 33

        String signal = rsi.signal(new BigDecimal("20.00"));

        assertEquals("OVERSOLD", signal,
                "RSI of 20 should be OVERSOLD (threshold 33)");
    }

    @Test
    void signal_overbought_whenRsiAboveOverboughtThreshold() {
        RSIIndicator rsi = new RSIIndicator(); // overbought = 60

        String signal = rsi.signal(new BigDecimal("75.00"));

        assertEquals("OVERBOUGHT", signal,
                "RSI of 75 should be OVERBOUGHT (threshold 60)");
    }

    @Test
    void signal_weak_whenRsiBetweenOversoldAndNeutral() {
        RSIIndicator rsi = new RSIIndicator(); // oversold=33, neutral=40

        String signal = rsi.signal(new BigDecimal("35.00"));

        assertEquals("WEAK", signal,
                "RSI of 35 should be WEAK (between oversold 33 and neutral 40)");
    }

    @Test
    void signal_neutral_whenRsiBetweenNeutralAndOverbought() {
        RSIIndicator rsi = new RSIIndicator(); // neutral=40, overbought=60

        String signal = rsi.signal(new BigDecimal("50.00"));

        assertEquals("NEUTRAL", signal,
                "RSI of 50 should be NEUTRAL (between neutral 40 and overbought 60)");
    }

    @Test
    void signal_null_returnsNeutral() {
        RSIIndicator rsi = new RSIIndicator();

        String signal = rsi.signal(null);

        assertEquals("NEUTRAL", signal, "Null RSI value should return NEUTRAL");
    }

    @Test
    void calculate_customPeriodAndThresholds() {
        RSIIndicator rsi = new RSIIndicator(7, 20.0, 50.0, 80.0);
        // With period 7, need > 7 candles
        List<Candle> candles = buildMixedCandles(20, 70.0);

        List<BigDecimal> values = rsi.calculate(candles);

        assertFalse(values.isEmpty(), "Custom period RSI should produce results");

        // Verify custom thresholds work
        assertEquals("OVERSOLD", rsi.signal(new BigDecimal("15.00")));
        assertEquals("OVERBOUGHT", rsi.signal(new BigDecimal("85.00")));
    }

    @Test
    void current_returnsMostRecentRsiValue() {
        RSIIndicator rsi = new RSIIndicator();
        List<Candle> candles = buildMixedCandles(30, 70.0);

        BigDecimal current = rsi.current(candles);
        List<BigDecimal> all = rsi.calculate(candles);

        assertNotNull(current);
        assertEquals(all.get(all.size() - 1), current,
                "current() should return the last element of calculate()");
    }
}
