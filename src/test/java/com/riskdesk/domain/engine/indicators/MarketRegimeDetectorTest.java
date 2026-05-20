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

    // ── Momentum fast-path tests ────────────────────────────────────────────────
    //
    // The fast-path catches directional cassures the lagging EMA/BB logic misses.
    // Threshold: |priceMove over 6 candles| > 1.8 * ATR * sqrt(6) ≈ 4.41 * ATR.
    //
    // Reference incident (MCL 2026-04-30): -152¢ in 58 min on 5m candles. With an
    // ATR around 30¢, expected noise = 30 * sqrt(6) ≈ 73¢; threshold ≈ 132¢. The
    // -152¢ move is comfortably above → flips the regime to TRENDING_DOWN even
    // when EMAs / BB still report CHOPPY.

    @Test
    void fastPath_strongBearishMove_returnsTrendingDownEvenWithChoppyEmas() {
        // CHOPPY EMAs: EMA9 above EMA200 but EMA50 below — no clear alignment.
        // Recent closes drop -159 over the lookback window with ATR 30 → exceeds threshold.
        var closes = List.of(
            bd("100.0"), bd("99.0"), bd("85.0"), bd("70.0"), bd("55.0"), bd("40.0"), bd("-60.0"));
        // closes[size-6] = 99.0 ; closes[size-1] = -60.0 ; move = -159 (|move| > 132)
        String regime = detector.detect(
            bd("105"), bd("94"), bd("100"),  // legacy logic = CHOPPY
            true, closes, bd("30.0"));
        assertEquals("TRENDING_DOWN", regime);
    }

    @Test
    void fastPath_strongBullishMove_returnsTrendingUpEvenWithRangingEmas() {
        // RANGING EMAs: ema9/ema50 close + bb contracting. Closes rise +160 over the lookback.
        var closes = List.of(
            bd("0"), bd("100"), bd("110"), bd("125"), bd("140"), bd("180"), bd("260"));
        // closes[size-6] = 100 ; closes[size-1] = 260 ; move = +160 ; threshold ≈ 132
        String regime = detector.detect(
            bd("100.10"), bd("100.00"), bd("95"),  // legacy logic = RANGING (close + bb contracting)
            false, closes, bd("30.0"));
        assertEquals("TRENDING_UP", regime);
    }

    @Test
    void fastPath_moveBelowThreshold_fallsBackToLegacyLogic() {
        // Move = +45 over 6 candles. Threshold = 1.8 * 20 * sqrt(6) ≈ 88. Move < threshold.
        // Legacy logic: bullish alignment + bbExpanding = TRENDING_UP.
        var closes = List.of(
            bd("100"), bd("105"), bd("110"), bd("120"), bd("130"), bd("140"), bd("150"));
        // closes[size-6] = 105 ; closes[size-1] = 150 ; move = +45 (< threshold ≈ 88)
        String regime = detector.detect(
            bd("105"), bd("100"), bd("95"),  // legacy logic = TRENDING_UP
            true, closes, bd("20.0"));
        assertEquals("TRENDING_UP", regime);
    }

    @Test
    void fastPath_moveBelowThreshold_choppyEmas_staysChoppy() {
        // Move = small. Legacy CHOPPY. Fast-path doesn't trigger → CHOPPY survives.
        var closes = List.of(
            bd("100"), bd("101"), bd("100"), bd("102"), bd("100"), bd("101"), bd("100"));
        String regime = detector.detect(
            bd("105"), bd("94"), bd("100"),  // crossed EMAs → CHOPPY
            true, closes, bd("30.0"));
        assertEquals("CHOPPY", regime);
    }

    @Test
    void fastPath_insufficientCandles_fallsBackToLegacyLogic() {
        // Only 4 closes (need >= 6). Fast-path is skipped, legacy logic runs.
        var closes = List.of(bd("100"), bd("110"), bd("120"), bd("150"));
        String regime = detector.detect(
            bd("105"), bd("94"), bd("100"),  // CHOPPY
            true, closes, bd("10.0"));
        assertEquals("CHOPPY", regime);
    }

    @Test
    void fastPath_nullCloses_fallsBackToLegacyLogic() {
        String regime = detector.detect(
            bd("105"), bd("100"), bd("95"), true, null, bd("20.0"));
        assertEquals("TRENDING_UP", regime);
    }

    @Test
    void fastPath_nullAtr_fallsBackToLegacyLogic() {
        var closes = List.of(
            bd("100"), bd("99"), bd("85"), bd("70"), bd("55"), bd("40"), bd("-60"));
        // even with a huge move, missing ATR disables fast-path
        String regime = detector.detect(
            bd("105"), bd("94"), bd("100"), true, closes, null);
        assertEquals("CHOPPY", regime);  // legacy logic for crossed EMAs
    }

    @Test
    void fastPath_zeroAtr_fallsBackToLegacyLogic() {
        var closes = List.of(
            bd("100"), bd("99"), bd("85"), bd("70"), bd("55"), bd("40"), bd("-60"));
        String regime = detector.detect(
            bd("105"), bd("94"), bd("100"), true, closes, BigDecimal.ZERO);
        assertEquals("CHOPPY", regime);
    }

    @Test
    void fastPathDirection_returnsZeroForInsufficientData() {
        assertEquals(0, detector.fastPathDirection(null, bd("10")));
        assertEquals(0, detector.fastPathDirection(List.of(), bd("10")));
        assertEquals(0, detector.fastPathDirection(List.of(bd("100"), bd("110")), bd("10")));
    }

    @Test
    void fastPathDirection_signsMatchPriceMove() {
        var bullish = List.of(
            bd("0"), bd("100"), bd("110"), bd("125"), bd("140"), bd("180"), bd("260"));
        var bearish = List.of(
            bd("0"), bd("260"), bd("180"), bd("140"), bd("125"), bd("110"), bd("100"));
        assertEquals(+1, detector.fastPathDirection(bullish, bd("30")));
        assertEquals(-1, detector.fastPathDirection(bearish, bd("30")));
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
