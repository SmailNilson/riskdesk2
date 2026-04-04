package com.riskdesk.domain.engine.indicators;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketRegimeDetectorTest {

    private final MarketRegimeDetector detector = new MarketRegimeDetector();

    @Test
    void trendingUp() {
        assertEquals("TRENDING_UP", detector.detect(
            bd("105"), bd("100"), bd("95"), true));
    }

    @Test
    void trendingDown() {
        assertEquals("TRENDING_DOWN", detector.detect(
            bd("95"), bd("100"), bd("105"), true));
    }

    @Test
    void ranging() {
        // EMAs close together, BB contracting
        assertEquals("RANGING", detector.detect(
            bd("100.10"), bd("100.00"), bd("95"), false));
    }

    @Test
    void choppy_crossedEmas() {
        // EMA9 > EMA200 but EMA50 < EMA200 = crossed, no clear direction
        assertEquals("CHOPPY", detector.detect(
            bd("105"), bd("94"), bd("100"), true));
    }

    @Test
    void choppy_bullishAlignmentButContracting() {
        // Bullish alignment but BB not expanding
        assertEquals("CHOPPY", detector.detect(
            bd("105"), bd("100"), bd("95"), false));
    }

    @Test
    void nullInputsReturnChoppy() {
        assertEquals("CHOPPY", detector.detect(null, bd("100"), bd("95"), true));
        assertEquals("CHOPPY", detector.detect(bd("105"), null, bd("95"), true));
        assertEquals("CHOPPY", detector.detect(bd("105"), bd("100"), null, true));
    }

    @Test
    void durationCandles() {
        // 5 candles, last 3 are TRENDING_UP
        var ema9 = List.of(bd("95"), bd("96"), bd("105"), bd("106"), bd("107"));
        var ema50 = List.of(bd("100"), bd("100"), bd("100"), bd("100"), bd("100"));
        var ema200 = List.of(bd("105"), bd("105"), bd("95"), bd("95"), bd("95"));
        var bbExp = List.of(true, true, true, true, true);

        assertEquals(3, detector.durationCandles(ema9, ema50, ema200, bbExp));
    }

    @Test
    void htfAlignmentSameDirection() {
        assertTrue(detector.htfAligned("TRENDING_UP", "TRENDING_UP"));
        assertTrue(detector.htfAligned("TRENDING_DOWN", "TRENDING_DOWN"));
    }

    @Test
    void htfAlignmentDifferentRegimes() {
        assertFalse(detector.htfAligned("TRENDING_UP", "TRENDING_DOWN"));
        assertFalse(detector.htfAligned("TRENDING_UP", "RANGING"));
        assertFalse(detector.htfAligned("RANGING", "RANGING"));
        assertFalse(detector.htfAligned(null, "TRENDING_UP"));
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
