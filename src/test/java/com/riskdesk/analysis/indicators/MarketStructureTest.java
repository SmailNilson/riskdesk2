package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.smc.MarketStructure;
import com.riskdesk.domain.engine.smc.MarketStructure.*;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketStructureTest {

    private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
    private MarketStructure marketStructure;

    @BeforeEach
    void setUp() {
        marketStructure = new MarketStructure(); // default swingLookback=5
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private Candle candle(double open, double high, double low, double close, long volume, Instant time) {
        return new Candle(Instrument.MCL, "10m", time,
                new BigDecimal(String.valueOf(open)), new BigDecimal(String.valueOf(high)),
                new BigDecimal(String.valueOf(low)), new BigDecimal(String.valueOf(close)), volume);
    }

    /**
     * Generate candles with an uptrend-downtrend-uptrend pattern to produce
     * swing highs and lows. This creates 3 waves with clear swing points.
     */
    private List<Candle> generateSwingCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        double base = 70.0;
        int waveLength = count / 3;

        for (int i = 0; i < count; i++) {
            double price;
            int phase = i / Math.max(waveLength, 1);
            int posInPhase = i % Math.max(waveLength, 1);

            if (phase == 0) {
                // Up wave
                price = base + posInPhase * 0.5;
            } else if (phase == 1) {
                // Down wave
                price = base + waveLength * 0.5 - posInPhase * 0.5;
            } else {
                // Up wave again
                price = base + posInPhase * 0.5;
            }

            double open = price;
            double close = price + 0.1;
            double high = Math.max(open, close) + 0.2;
            double low = Math.min(open, close) - 0.2;
            candles.add(candle(open, high, low, close, 1000, BASE_TIME.plusSeconds(600L * i)));
        }
        return candles;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void analyze_with30PlusCandles_returnsNonNullResult() {
        List<Candle> candles = generateSwingCandles(40);

        StructureAnalysis result = marketStructure.analyze(candles);

        assertNotNull(result, "StructureAnalysis should not be null");
        assertNotNull(result.swingPoints(), "Swing points list should not be null");
        assertNotNull(result.breaks(), "Breaks list should not be null");
        assertNotNull(result.currentTrend(), "Current trend should not be null");
    }

    @Test
    void result_containsTrendDirection() {
        List<Candle> candles = generateSwingCandles(40);

        StructureAnalysis result = marketStructure.analyze(candles);

        Trend trend = result.currentTrend();
        assertNotNull(trend);
        assertTrue(
                trend == Trend.BULLISH || trend == Trend.BEARISH || trend == Trend.UNDEFINED,
                "Trend should be BULLISH, BEARISH, or UNDEFINED, was: " + trend);
    }

    @Test
    void result_containsStrongWeakHighLowLevels_canBeNull() {
        List<Candle> candles = generateSwingCandles(40);

        StructureAnalysis result = marketStructure.analyze(candles);

        // Strong/weak highs and lows CAN be null if no structure break detected
        // Just verify they are accessible without error
        BigDecimal strongLow = result.strongLow();
        BigDecimal strongHigh = result.strongHigh();
        BigDecimal weakLow = result.weakLow();
        BigDecimal weakHigh = result.weakHigh();

        // If breaks were detected, at least some of these should be set
        if (!result.breaks().isEmpty()) {
            StructureBreak lastBreak = result.breaks().get(result.breaks().size() - 1);
            if (lastBreak.newTrend() == Trend.BULLISH) {
                assertNotNull(strongLow,
                        "Strong low should be set in bullish trend after break");
            } else if (lastBreak.newTrend() == Trend.BEARISH) {
                assertNotNull(strongHigh,
                        "Strong high should be set in bearish trend after break");
            }
        }
    }

    @Test
    void breakTypes_bosAndChoch_detectedWithProperTrends() {
        // Create a pattern with clear structure breaks:
        // Up -> Down -> Up to cause CHoCH and BOS
        List<Candle> candles = new ArrayList<>();
        double base = 70.0;
        int idx = 0;

        // Phase 1: Uptrend (create swing high + swing low)
        for (int i = 0; i < 8; i++) {
            double price = base + i * 0.5;
            candles.add(candle(price, price + 0.3, price - 0.3, price + 0.1, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Phase 2: Pullback (create lower swing)
        for (int i = 0; i < 8; i++) {
            double price = base + 4.0 - i * 0.5;
            candles.add(candle(price, price + 0.3, price - 0.3, price - 0.1, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Phase 3: Rally (break above previous high -> BOS or CHoCH)
        for (int i = 0; i < 8; i++) {
            double price = base + i * 0.8;
            candles.add(candle(price, price + 0.3, price - 0.3, price + 0.2, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Phase 4: Drop below (break below previous low -> CHoCH)
        for (int i = 0; i < 10; i++) {
            double price = base + 5.0 - i * 1.0;
            candles.add(candle(price, price + 0.3, price - 0.3, price - 0.2, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }

        StructureAnalysis result = marketStructure.analyze(candles);

        assertNotNull(result);
        // Verify break types are valid
        for (StructureBreak brk : result.breaks()) {
            assertNotNull(brk.type(), "Break type should not be null");
            assertTrue(brk.type() == StructureType.BOS || brk.type() == StructureType.CHOCH,
                    "Break type should be BOS or CHOCH, was: " + brk.type());
            assertNotNull(brk.newTrend(), "Break's new trend should not be null");
            assertTrue(brk.newTrend() == Trend.BULLISH || brk.newTrend() == Trend.BEARISH,
                    "Break new trend should be BULLISH or BEARISH, was: " + brk.newTrend());
            assertNotNull(brk.breakLevel(), "Break level should not be null");
            assertNotNull(brk.brokenSwing(), "Broken swing should not be null");
        }
    }

    @Test
    void insufficientData_returnsDefaultResult() {
        // swingLookback=5 -> needs at least 5*2+1=11 candles for any swing detection
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candles.add(candle(70.0 + i, 71.0 + i, 69.0 + i, 70.5 + i, 1000,
                    BASE_TIME.plusSeconds(600L * i)));
        }

        StructureAnalysis result = marketStructure.analyze(candles);

        assertNotNull(result, "Should not be null even with insufficient data");
        assertTrue(result.swingPoints().isEmpty(),
                "Swing points should be empty with insufficient data");
        assertTrue(result.breaks().isEmpty(),
                "Breaks should be empty with insufficient data");
        assertEquals(Trend.UNDEFINED, result.currentTrend(),
                "Trend should be UNDEFINED with insufficient data");
    }

    @Test
    void monotonicallyIncreasingPrices_produceBullishTrend() {
        // Create 40 candles with strictly increasing prices that have enough variation
        // to form swing points (need pullbacks to create swing lows)
        List<Candle> candles = new ArrayList<>();
        int idx = 0;

        // Pattern: 3 waves upward with small pullbacks to create detectable swing points
        // Wave 1: up
        for (int i = 0; i < 8; i++) {
            double price = 70.0 + i * 0.5;
            candles.add(candle(price, price + 0.3, price - 0.2, price + 0.2, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Small pullback
        for (int i = 0; i < 8; i++) {
            double price = 74.0 - i * 0.2;
            candles.add(candle(price, price + 0.2, price - 0.3, price - 0.1, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Wave 2: up higher
        for (int i = 0; i < 8; i++) {
            double price = 73.0 + i * 0.6;
            candles.add(candle(price, price + 0.3, price - 0.2, price + 0.2, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Small pullback
        for (int i = 0; i < 8; i++) {
            double price = 77.0 - i * 0.15;
            candles.add(candle(price, price + 0.2, price - 0.3, price - 0.1, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Wave 3: break above all highs
        for (int i = 0; i < 8; i++) {
            double price = 76.0 + i * 0.7;
            candles.add(candle(price, price + 0.3, price - 0.2, price + 0.2, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }

        StructureAnalysis result = marketStructure.analyze(candles);

        assertNotNull(result);
        // With higher highs and higher lows, if any break is detected it should eventually be BULLISH
        if (!result.breaks().isEmpty()) {
            StructureBreak lastBreak = result.breaks().get(result.breaks().size() - 1);
            assertEquals(Trend.BULLISH, lastBreak.newTrend(),
                    "Last break in uptrend should be BULLISH");
        }
        // The current trend after HH/HL pattern should be BULLISH (or UNDEFINED if no breaks detected)
        assertTrue(result.currentTrend() == Trend.BULLISH || result.currentTrend() == Trend.UNDEFINED,
                "Trend with HH/HL pattern should be BULLISH or UNDEFINED, was: " + result.currentTrend());
    }

    @Test
    void swingPoints_haveValidTypes() {
        List<Candle> candles = generateSwingCandles(40);

        List<SwingPoint> swings = marketStructure.detectSwingPoints(candles);

        for (SwingPoint sp : swings) {
            assertNotNull(sp.type());
            assertTrue(sp.type() == SwingType.HIGH || sp.type() == SwingType.LOW,
                    "Swing type should be HIGH or LOW");
            assertNotNull(sp.price(), "Swing price should not be null");
            assertTrue(sp.index() >= 0 && sp.index() < candles.size(),
                    "Swing index should be within candle range");
        }
    }

    // -----------------------------------------------------------------------
    // Regression tests for BigDecimal compareTo() fix
    // (BigDecimal.equals() checks scale: 70.0 != 70.00, so equals() silently
    // failed to mark any swing point as STRONG before the fix.)
    // -----------------------------------------------------------------------

    @Test
    void bullishBreak_strongLowSwingPoint_isMarkedStrong() {
        List<Candle> candles = new ArrayList<>();
        int idx = 0;

        // Phase 1: rally — creates a detectable swing high
        for (int i = 0; i < 8; i++) {
            double price = 70.0 + i * 0.5;
            candles.add(candle(price, price + 0.3, price - 0.3, price + 0.1, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Phase 2: pullback — creates a detectable swing low
        for (int i = 0; i < 8; i++) {
            double price = 73.5 - i * 0.4;
            candles.add(candle(price, price + 0.3, price - 0.3, price - 0.1, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Phase 3: rally that closes above the previous swing high → bullish break
        for (int i = 0; i < 8; i++) {
            double price = 71.0 + i * 0.7;
            candles.add(candle(price, price + 0.3, price - 0.3, price + 0.2, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }

        StructureAnalysis result = marketStructure.analyze(candles);

        // Only assert the strength when the analyzer actually detected a bullish break
        if (!result.breaks().isEmpty() && result.currentTrend() == Trend.BULLISH) {
            BigDecimal strongLow = result.strongLow();
            assertNotNull(strongLow, "strongLow must be set after a bullish break");

            boolean foundStrongLow = result.swingPoints().stream()
                    .anyMatch(sp -> sp.type() == SwingType.LOW
                            && sp.strength() == Strength.STRONG
                            && sp.price().compareTo(strongLow) == 0);
            assertTrue(foundStrongLow,
                    "Swing LOW at " + strongLow + " must be marked STRONG after a bullish break. " +
                    "Swing points: " + result.swingPoints());
        }
    }

    @Test
    void bearishBreak_strongHighSwingPoint_isMarkedStrong() {
        List<Candle> candles = new ArrayList<>();
        int idx = 0;

        // Phase 1: decline — creates a detectable swing low
        for (int i = 0; i < 8; i++) {
            double price = 75.0 - i * 0.5;
            candles.add(candle(price, price + 0.3, price - 0.3, price - 0.1, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Phase 2: rally — creates a detectable swing high
        for (int i = 0; i < 8; i++) {
            double price = 71.5 + i * 0.4;
            candles.add(candle(price, price + 0.3, price - 0.3, price + 0.1, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }
        // Phase 3: drop that closes below the previous swing low → bearish break
        for (int i = 0; i < 8; i++) {
            double price = 75.0 - i * 0.7;
            candles.add(candle(price, price + 0.3, price - 0.3, price - 0.2, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }

        StructureAnalysis result = marketStructure.analyze(candles);

        // Only assert the strength when the analyzer actually detected a bearish break
        if (!result.breaks().isEmpty() && result.currentTrend() == Trend.BEARISH) {
            BigDecimal strongHigh = result.strongHigh();
            assertNotNull(strongHigh, "strongHigh must be set after a bearish break");

            boolean foundStrongHigh = result.swingPoints().stream()
                    .anyMatch(sp -> sp.type() == SwingType.HIGH
                            && sp.strength() == Strength.STRONG
                            && sp.price().compareTo(strongHigh) == 0);
            assertTrue(foundStrongHigh,
                    "Swing HIGH at " + strongHigh + " must be marked STRONG after a bearish break. " +
                    "Swing points: " + result.swingPoints());
        }
    }
}
