package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.indicators.SupertrendIndicator;
import com.riskdesk.domain.engine.indicators.SupertrendIndicator.SupertrendResult;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SupertrendIndicatorTest {

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
     * Build strongly uptrending candles with increasing highs, lows, and close.
     */
    private List<Candle> buildUptrendCandles(int count, double startClose, double step) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = startClose + i * step;
            double open = close - step * 0.3;
            double high = close + step * 0.5;
            double low = close - step * 0.5;
            candles.add(candle(open, high, low, close, 1000));
        }
        return candles;
    }

    /**
     * Build strongly downtrending candles with decreasing highs, lows, and close.
     */
    private List<Candle> buildDowntrendCandles(int count, double startClose, double step) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = startClose - i * step;
            double open = close + step * 0.3;
            double high = close + step * 0.5;
            double low = close - step * 0.5;
            candles.add(candle(open, high, low, close, 1000));
        }
        return candles;
    }

    /**
     * Build candles with mixed (oscillating) movement.
     */
    private List<Candle> buildMixedCandles(int count, double basePrice) {
        List<Candle> candles = new ArrayList<>();
        double close = basePrice;
        for (int i = 0; i < count; i++) {
            double open = close - 0.2;
            double high = close + 1.5;
            double low = close - 1.5;
            candles.add(candle(open, high, low, close, 1000));
            close = (i % 2 == 0) ? close + 0.5 : close - 0.3;
        }
        return candles;
    }

    // --------------- tests ---------------

    @Test
    void calculate_with30Candles_returnsNonNullResults() {
        SupertrendIndicator st = new SupertrendIndicator(); // period=10, factor=3.0
        List<Candle> candles = buildMixedCandles(30, 70.0);

        List<SupertrendResult> results = st.calculate(candles);

        assertFalse(results.isEmpty(), "Supertrend should produce results with 30 candles (atrPeriod=10)");
        for (SupertrendResult r : results) {
            assertNotNull(r.value(), "Supertrend value should not be null");
            assertNotNull(r.upperBand(), "Upper band should not be null");
            assertNotNull(r.lowerBand(), "Lower band should not be null");
        }
    }

    @Test
    void calculate_uptrendData_supertrendBelowClose() {
        SupertrendIndicator st = new SupertrendIndicator();
        // Strong uptrend with large steps to keep close well above bands
        List<Candle> candles = buildUptrendCandles(30, 50.0, 2.0);

        List<SupertrendResult> results = st.calculate(candles);

        assertFalse(results.isEmpty());

        // In a strong uptrend, the last several results should show isUptrend = true
        // and the supertrend value should be below the close price
        SupertrendResult last = results.get(results.size() - 1);
        BigDecimal lastClose = candles.get(candles.size() - 1).getClose();

        assertTrue(last.isUptrend(),
                "Strong uptrend data should result in uptrend=true");
        assertTrue(last.value().compareTo(lastClose) < 0,
                "In uptrend, supertrend value (" + last.value() + ") should be below close (" + lastClose + ")");
    }

    @Test
    void calculate_downtrendData_supertrendAboveClose() {
        SupertrendIndicator st = new SupertrendIndicator();
        // Strong downtrend with large steps
        List<Candle> candles = buildDowntrendCandles(30, 100.0, 2.0);

        List<SupertrendResult> results = st.calculate(candles);

        assertFalse(results.isEmpty());

        SupertrendResult last = results.get(results.size() - 1);
        BigDecimal lastClose = candles.get(candles.size() - 1).getClose();

        assertFalse(last.isUptrend(),
                "Strong downtrend data should result in uptrend=false");
        assertTrue(last.value().compareTo(lastClose) > 0,
                "In downtrend, supertrend value (" + last.value() + ") should be above close (" + lastClose + ")");
    }

    @Test
    void calculate_insufficientData_returnsEmpty() {
        SupertrendIndicator st = new SupertrendIndicator(); // atrPeriod = 10
        // Need > atrPeriod candles. With 10 candles (size <= period), returns empty.
        List<Candle> candles = buildMixedCandles(10, 70.0);

        List<SupertrendResult> results = st.calculate(candles);

        assertTrue(results.isEmpty(),
                "Supertrend with exactly 'atrPeriod' candles should return empty");
    }

    @Test
    void calculate_with5Candles_returnsEmpty() {
        SupertrendIndicator st = new SupertrendIndicator();
        List<Candle> candles = buildMixedCandles(5, 70.0);

        List<SupertrendResult> results = st.calculate(candles);

        assertTrue(results.isEmpty(),
                "Supertrend with 5 candles (< atrPeriod=10) should return empty");
    }

    @Test
    void current_returnsLastResult() {
        SupertrendIndicator st = new SupertrendIndicator();
        List<Candle> candles = buildMixedCandles(30, 70.0);

        SupertrendResult current = st.current(candles);
        List<SupertrendResult> all = st.calculate(candles);

        assertNotNull(current);
        assertEquals(all.get(all.size() - 1).value(), current.value());
        assertEquals(all.get(all.size() - 1).isUptrend(), current.isUptrend());
    }

    @Test
    void current_insufficientData_returnsNull() {
        SupertrendIndicator st = new SupertrendIndicator();
        List<Candle> candles = buildMixedCandles(5, 70.0);

        SupertrendResult current = st.current(candles);

        assertNull(current, "current() should return null for insufficient data");
    }

    @Test
    void calculate_upperBandAboveLowerBand() {
        SupertrendIndicator st = new SupertrendIndicator();
        List<Candle> candles = buildMixedCandles(30, 70.0);

        List<SupertrendResult> results = st.calculate(candles);

        assertFalse(results.isEmpty());
        for (SupertrendResult r : results) {
            assertTrue(r.upperBand().compareTo(r.lowerBand()) > 0,
                    "Upper band (" + r.upperBand() + ") should be above lower band (" + r.lowerBand() + ")");
        }
    }

    @Test
    void calculate_customParameters() {
        SupertrendIndicator st = new SupertrendIndicator(7, 2.0);
        List<Candle> candles = buildMixedCandles(20, 70.0);

        List<SupertrendResult> results = st.calculate(candles);

        assertFalse(results.isEmpty(),
                "Supertrend with custom atrPeriod=7 should produce results with 20 candles");
    }
}
