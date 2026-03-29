package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.indicators.VWAPIndicator;
import com.riskdesk.domain.engine.indicators.VWAPIndicator.VWAPResult;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VWAPIndicatorTest {

    // --------------- helper ---------------

    private Candle candle(double open, double high, double low, double close, long volume) {
        return candleAt(Instant.now(), open, high, low, close, volume);
    }

    private Candle candleAt(Instant timestamp, double open, double high, double low, double close, long volume) {
        return new Candle(
                Instrument.MCL, "10m", timestamp,
                new BigDecimal(String.valueOf(open)),
                new BigDecimal(String.valueOf(high)),
                new BigDecimal(String.valueOf(low)),
                new BigDecimal(String.valueOf(close)),
                volume
        );
    }

    /**
     * Build candles with varying prices and volumes.
     */
    private List<Candle> buildCandles(int count, double basePrice) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double close = basePrice + (i % 3 == 0 ? 1.0 : -0.5) + i * 0.1;
            double high = close + 1.5;
            double low = close - 1.5;
            double open = close - 0.3;
            long volume = 500 + i * 100;
            candles.add(candle(open, high, low, close, volume));
        }
        return candles;
    }

    /**
     * Build candles all with the same typical price: (H + L + C) / 3 = target.
     * We set H = target + delta, L = target - delta, C = target.
     */
    private List<Candle> buildEqualTypicalPriceCandles(int count, double typicalPrice, long volume) {
        List<Candle> candles = new ArrayList<>();
        double delta = 1.0;
        for (int i = 0; i < count; i++) {
            double high = typicalPrice + delta;
            double low = typicalPrice - delta;
            double close = typicalPrice; // TP = (high + low + close) / 3 = (tp+d + tp-d + tp) / 3 = tp
            double open = typicalPrice - 0.2;
            candles.add(candle(open, high, low, close, volume));
        }
        return candles;
    }

    // --------------- tests ---------------

    @Test
    void calculate_vwapBetweenLowestLowAndHighestHigh() {
        VWAPIndicator vwap = new VWAPIndicator();
        List<Candle> candles = buildCandles(20, 70.0);

        List<VWAPResult> results = vwap.calculate(candles);

        assertFalse(results.isEmpty());

        // Find global low and high
        BigDecimal lowestLow = candles.stream()
                .map(Candle::getLow)
                .min(BigDecimal::compareTo)
                .orElseThrow();
        BigDecimal highestHigh = candles.stream()
                .map(Candle::getHigh)
                .max(BigDecimal::compareTo)
                .orElseThrow();

        for (VWAPResult r : results) {
            assertNotNull(r.vwap());
            assertTrue(r.vwap().compareTo(lowestLow) >= 0,
                    "VWAP (" + r.vwap() + ") should be >= lowest low (" + lowestLow + ")");
            assertTrue(r.vwap().compareTo(highestHigh) <= 0,
                    "VWAP (" + r.vwap() + ") should be <= highest high (" + highestHigh + ")");
        }
    }

    @Test
    void calculate_upperBandAboveVwapAboveLowerBand() {
        VWAPIndicator vwap = new VWAPIndicator();
        List<Candle> candles = buildCandles(20, 70.0);

        List<VWAPResult> results = vwap.calculate(candles);

        assertFalse(results.isEmpty());

        // Skip the first result (single candle: bands may equal VWAP since stddev=0)
        for (int i = 1; i < results.size(); i++) {
            VWAPResult r = results.get(i);
            assertTrue(r.upperBand().compareTo(r.vwap()) >= 0,
                    "Upper band (" + r.upperBand() + ") should be >= VWAP (" + r.vwap() + ")");
            assertTrue(r.lowerBand().compareTo(r.vwap()) <= 0,
                    "Lower band (" + r.lowerBand() + ") should be <= VWAP (" + r.vwap() + ")");
        }
    }

    @Test
    void calculate_singleCandle_vwapEqualsTypicalPrice() {
        VWAPIndicator vwap = new VWAPIndicator();

        double high = 72.0;
        double low = 68.0;
        double close = 71.0;
        double open = 69.5;
        List<Candle> candles = List.of(candle(open, high, low, close, 1000));

        List<VWAPResult> results = vwap.calculate(candles);

        assertEquals(1, results.size());

        // Typical price = (72 + 68 + 71) / 3 = 211 / 3 = 70.33333...
        BigDecimal expectedTp = new BigDecimal(String.valueOf(high))
                .add(new BigDecimal(String.valueOf(low)))
                .add(new BigDecimal(String.valueOf(close)))
                .divide(BigDecimal.valueOf(3), 5, RoundingMode.HALF_UP);

        VWAPResult result = results.get(0);
        assertEquals(0, result.vwap().compareTo(expectedTp),
                "Single candle VWAP (" + result.vwap() + ") should equal typical price (" + expectedTp + ")");
    }

    @Test
    void calculate_allCandlesEqualTypicalPrice_vwapEqualsThatPrice() {
        VWAPIndicator vwap = new VWAPIndicator();
        double typicalPrice = 72.0;
        List<Candle> candles = buildEqualTypicalPriceCandles(15, typicalPrice, 1000);

        List<VWAPResult> results = vwap.calculate(candles);

        assertFalse(results.isEmpty());
        assertEquals(candles.size(), results.size());

        BigDecimal expectedTp = new BigDecimal(String.valueOf(typicalPrice));

        for (VWAPResult r : results) {
            // VWAP should equal the constant typical price
            // Allow small rounding differences (scale/rounding in the impl)
            BigDecimal diff = r.vwap().subtract(expectedTp).abs();
            assertTrue(diff.compareTo(new BigDecimal("0.01")) < 0,
                    "VWAP (" + r.vwap() + ") should equal typical price (" + expectedTp + "), diff=" + diff);
        }
    }

    @Test
    void calculate_emptyCandles_returnsEmpty() {
        VWAPIndicator vwap = new VWAPIndicator();
        List<VWAPResult> results = vwap.calculate(List.of());

        assertTrue(results.isEmpty(), "Empty candle list should produce empty results");
    }

    @Test
    void current_returnsLastResult() {
        VWAPIndicator vwap = new VWAPIndicator();
        List<Candle> candles = buildCandles(20, 70.0);

        VWAPResult current = vwap.current(candles);
        List<VWAPResult> all = vwap.calculate(candles);

        assertNotNull(current);
        assertEquals(all.get(all.size() - 1).vwap(), current.vwap());
        assertEquals(all.get(all.size() - 1).upperBand(), current.upperBand());
        assertEquals(all.get(all.size() - 1).lowerBand(), current.lowerBand());
    }

    @Test
    void current_emptyCandles_returnsNull() {
        VWAPIndicator vwap = new VWAPIndicator();

        VWAPResult current = vwap.current(List.of());

        assertNull(current, "current() should return null for empty candle list");
    }

    @Test
    void calculate_resultCountMatchesCandleCount() {
        VWAPIndicator vwap = new VWAPIndicator();
        List<Candle> candles = buildCandles(10, 70.0);

        List<VWAPResult> results = vwap.calculate(candles);

        assertEquals(candles.size(), results.size(),
                "VWAP should produce one result per candle");
    }

    @Test
    void calculate_singleCandle_bandsEqualVwap() {
        VWAPIndicator vwap = new VWAPIndicator();
        // With a single candle, stddev = 0, so bands should equal VWAP
        List<Candle> candles = List.of(candle(70.0, 72.0, 68.0, 71.0, 1000));

        List<VWAPResult> results = vwap.calculate(candles);

        assertEquals(1, results.size());
        VWAPResult r = results.get(0);

        // stddev of single TP from VWAP = 0, so upper = lower = VWAP
        assertEquals(0, r.upperBand().compareTo(r.vwap()),
                "Single candle upper band should equal VWAP");
        assertEquals(0, r.lowerBand().compareTo(r.vwap()),
                "Single candle lower band should equal VWAP");
    }

    @Test
    void calculate_resetsVwapWhenSessionDateChanges() {
        VWAPIndicator vwap = new VWAPIndicator();
        Instant beforeSessionClose = Instant.parse("2026-03-20T20:50:00Z"); // 16:50 ET
        Instant afterSessionClose = Instant.parse("2026-03-20T21:10:00Z");  // 17:10 ET

        List<Candle> candles = List.of(
                candleAt(beforeSessionClose, 99.0, 101.0, 98.0, 100.0, 1000),
                candleAt(beforeSessionClose.plus(10, ChronoUnit.MINUTES), 101.0, 103.0, 100.0, 102.0, 1000),
                candleAt(afterSessionClose, 199.0, 201.0, 198.0, 200.0, 1000)
        );

        List<VWAPResult> results = vwap.calculate(candles);

        assertEquals(3, results.size());
        assertEquals(new BigDecimal("199.66667"), results.get(2).vwap(),
                "VWAP should restart at the 17:00 ET CME session boundary");
    }

    @Test
    void calculate_doesNotResetAtMidnightInsideSameCmeSession() {
        VWAPIndicator vwap = new VWAPIndicator();
        Instant beforeMidnight = Instant.parse("2026-03-21T03:50:00Z"); // 23:50 ET on Mar 20
        Instant afterMidnight = Instant.parse("2026-03-21T04:10:00Z");  // 00:10 ET on Mar 21, same CME session

        List<Candle> candles = List.of(
                candleAt(beforeMidnight, 99.0, 101.0, 98.0, 100.0, 1000),
                candleAt(afterMidnight, 101.0, 103.0, 100.0, 102.0, 1000)
        );

        List<VWAPResult> results = vwap.calculate(candles);

        assertEquals(2, results.size());
        assertEquals(new BigDecimal("100.66667"), results.get(1).vwap(),
                "VWAP should continue across midnight until the next 17:00 ET session reset");
    }
}
