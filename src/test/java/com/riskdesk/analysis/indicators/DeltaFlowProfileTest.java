package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.indicators.DeltaFlowProfile;
import com.riskdesk.domain.engine.indicators.DeltaFlowProfile.DeltaBar;
import com.riskdesk.domain.engine.indicators.DeltaFlowProfile.DeltaFlowResult;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeltaFlowProfileTest {

    private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
    private DeltaFlowProfile profile;

    @BeforeEach
    void setUp() {
        profile = new DeltaFlowProfile(); // default lookback=20
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
    void compute_with30Candles_returnsNonNullResult() {
        List<Candle> candles = generateCandles(30);

        DeltaFlowResult result = profile.current(candles);

        assertNotNull(result, "DeltaFlowResult should not be null for 30 candles");
        assertNotNull(result.currentDelta());
        assertNotNull(result.cumulativeDelta());
        assertNotNull(result.rollingBuyVolume());
        assertNotNull(result.rollingSellVolume());
        assertNotNull(result.buyRatio());
        assertNotNull(result.bias());
    }

    @Test
    void bullishCandle_closeNearHigh_buyVolumeGreaterThanSellVolume() {
        // Candle where close is very near high -> strong buying pressure
        // CLV = ((Close - Low) - (High - Close)) / (High - Low)
        // Close near high => CLV is positive and large => buyVol > sellVol
        List<Candle> candles = new ArrayList<>();
        candles.add(candle(70.00, 71.00, 69.50, 70.95, 5000, BASE_TIME));

        List<DeltaBar> bars = profile.calculate(candles);

        assertFalse(bars.isEmpty());
        DeltaBar bar = bars.get(0);
        assertTrue(bar.buyVolume().doubleValue() > bar.sellVolume().doubleValue(),
                "Buy volume should exceed sell volume for bullish candle near high. " +
                "Buy=" + bar.buyVolume() + ", Sell=" + bar.sellVolume());
    }

    @Test
    void bearishCandle_closeNearLow_sellVolumeGreaterThanBuyVolume() {
        // Candle where close is very near low -> strong selling pressure
        // CLV negative => sellVol > buyVol
        List<Candle> candles = new ArrayList<>();
        candles.add(candle(71.00, 71.50, 69.50, 69.60, 5000, BASE_TIME));

        List<DeltaBar> bars = profile.calculate(candles);

        assertFalse(bars.isEmpty());
        DeltaBar bar = bars.get(0);
        assertTrue(bar.sellVolume().doubleValue() > bar.buyVolume().doubleValue(),
                "Sell volume should exceed buy volume for bearish candle near low. " +
                "Sell=" + bar.sellVolume() + ", Buy=" + bar.buyVolume());
    }

    @Test
    void buyRatio_isBetweenZeroAndOne() {
        List<Candle> candles = generateCandles(30);

        DeltaFlowResult result = profile.current(candles);

        assertNotNull(result);
        assertTrue(result.buyRatio().doubleValue() >= 0.0,
                "Buy ratio should be >= 0, was: " + result.buyRatio());
        assertTrue(result.buyRatio().doubleValue() <= 1.0,
                "Buy ratio should be <= 1, was: " + result.buyRatio());
    }

    @Test
    void cumulativeDelta_tracksProperly() {
        // Two bullish candles: cumulative delta should increase
        List<Candle> candles = new ArrayList<>();
        candles.add(candle(70.00, 71.00, 69.50, 70.90, 1000, BASE_TIME));
        candles.add(candle(71.00, 72.00, 70.50, 71.90, 1000, BASE_TIME.plusSeconds(600)));

        List<DeltaBar> bars = profile.calculate(candles);

        assertEquals(2, bars.size());
        // Both bullish => positive deltas => cumDelta increases
        assertTrue(bars.get(0).cumulativeDelta().doubleValue() > 0,
                "First cumDelta should be positive");
        assertTrue(bars.get(1).cumulativeDelta().doubleValue() > bars.get(0).cumulativeDelta().doubleValue(),
                "Second cumDelta should be greater than first (both bullish)");
    }

    @Test
    void bias_buying_whenBuyRatioAbove055() {
        // Create many strongly bullish candles so buyRatio > 0.55
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            double base = 70.0 + i * 0.5;
            // Close near high -> strong positive CLV -> mostly buy volume
            candles.add(candle(base, base + 1.0, base - 0.1, base + 0.95, 2000,
                    BASE_TIME.plusSeconds(600L * i)));
        }

        DeltaFlowResult result = profile.current(candles);

        assertNotNull(result);
        assertEquals("BUYING", result.bias(),
                "Bias should be BUYING when buy ratio > 0.55. BuyRatio=" + result.buyRatio());
    }

    @Test
    void bias_selling_whenBuyRatioBelow045() {
        // Create many strongly bearish candles so buyRatio < 0.45
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            double base = 80.0 - i * 0.5;
            // Close near low -> strong negative CLV -> mostly sell volume
            candles.add(candle(base, base + 0.1, base - 1.0, base - 0.95, 2000,
                    BASE_TIME.plusSeconds(600L * i)));
        }

        DeltaFlowResult result = profile.current(candles);

        assertNotNull(result);
        assertEquals("SELLING", result.bias(),
                "Bias should be SELLING when buy ratio < 0.45. BuyRatio=" + result.buyRatio());
    }

    @Test
    void bias_neutral_whenBuyRatioBetween045And055() {
        // Create balanced candles: half bullish, half bearish
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double base = 70.0;
            if (i % 2 == 0) {
                // Bullish
                candles.add(candle(base, base + 1.0, base - 0.1, base + 0.95, 2000,
                        BASE_TIME.plusSeconds(600L * i)));
            } else {
                // Bearish
                candles.add(candle(base, base + 0.1, base - 1.0, base - 0.95, 2000,
                        BASE_TIME.plusSeconds(600L * i)));
            }
        }

        DeltaFlowResult result = profile.current(candles);

        assertNotNull(result);
        // With perfectly alternating bull/bear candles of equal volume, ratio should be near 0.5
        double ratio = result.buyRatio().doubleValue();
        assertTrue(ratio >= 0.45 && ratio <= 0.55,
                "Buy ratio should be near 0.5 for balanced candles. Was: " + ratio);
        assertEquals("NEUTRAL", result.bias(),
                "Bias should be NEUTRAL for balanced candles. BuyRatio=" + result.buyRatio());
    }

    @Test
    void dojiCandle_splitsVolumeEqually() {
        // Doji: high == low -> volume split 50/50
        List<Candle> candles = new ArrayList<>();
        candles.add(candle(70.00, 70.00, 70.00, 70.00, 2000, BASE_TIME));

        List<DeltaBar> bars = profile.calculate(candles);

        assertFalse(bars.isEmpty());
        DeltaBar bar = bars.get(0);
        assertEquals(0, bar.buyVolume().compareTo(bar.sellVolume()),
                "Buy and sell volume should be equal for doji. Buy=" + bar.buyVolume() + ", Sell=" + bar.sellVolume());
        assertEquals(0, bar.delta().compareTo(BigDecimal.ZERO),
                "Delta should be zero for doji");
    }

    @Test
    void emptyCandles_returnsNull() {
        List<Candle> candles = new ArrayList<>();

        DeltaFlowResult result = profile.current(candles);

        assertNull(result, "Result should be null for empty candle list");
    }

    @Test
    void calculate_emptyCandles_returnsEmptyList() {
        List<Candle> candles = new ArrayList<>();

        List<DeltaBar> bars = profile.calculate(candles);

        assertNotNull(bars);
        assertTrue(bars.isEmpty(), "Bars should be empty for empty candle list");
    }
}
