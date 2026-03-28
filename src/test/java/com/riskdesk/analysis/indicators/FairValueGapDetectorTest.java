package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.smc.FairValueGapDetector;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FVG Detector Tests — UC-SMC-010: Threshold filtering, dedicated timeframe, visual extension.
 *
 * AC1: FVGs can be filtered by threshold
 * AC2: Visual extension calculates zone end time correctly
 * AC3: Unmitigated FVGs are tracked properly
 */
class FairValueGapDetectorTest {

    private static final Instrument TEST_INSTRUMENT = Instrument.MCL;

    private static Candle candle(long epochSecond, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        return new Candle(
                TEST_INSTRUMENT,
                "1h",
                Instant.ofEpochSecond(epochSecond),
                open, high, low, close,
                1000L
        );
    }

    /**
     * AC1: Verify weak FVG filtering — FVGs below threshold are excluded.
     */
    @Test
    void detect_withThreshold_filtersWeakFVGs() {
        // Setup: Create candles with a small gap
        List<Candle> candles = List.of(
                candle(100, BD("100"), BD("102"), BD("100"), BD("101")),
                candle(200, BD("101"), BD("103"), BD("101"), BD("102")), // FVG formation bar
                candle(300, BD("102.05"), BD("104"), BD("102.05"), BD("103")) // Bullish FVG: 102.05 - 102 = 0.05
        );

        // With threshold 0.1, this tiny 0.05 gap should be filtered
        FairValueGapDetector detector = new FairValueGapDetector(5, BD("0.1"), 0);
        List<FairValueGapDetector.FairValueGap> result = detector.detect(candles);

        // AC1: No gaps pass the 0.1 minimum threshold
        assertTrue(result.isEmpty(), "Expected gap to be filtered by threshold");
    }

    /**
     * AC1b: Verify threshold passes gap above minimum.
     */
    @Test
    void detect_withThreshold_passesSufficientFVGs() {
        // Setup: Create candles with larger gap
        List<Candle> candles = List.of(
                candle(100, BD("100"), BD("102"), BD("100"), BD("101")),
                candle(200, BD("101"), BD("103"), BD("101"), BD("102")), // FVG formation bar
                candle(300, BD("102.5"), BD("105"), BD("102.5"), BD("103")) // Bullish FVG: 102.5 - 102 = 0.5
        );

        // With threshold 0.1, this 0.5 gap should pass
        FairValueGapDetector detector = new FairValueGapDetector(5, BD("0.1"), 0);
        List<FairValueGapDetector.FairValueGap> result = detector.detect(candles);

        // AC1b: Gap passes threshold
        assertEquals(1, result.size(), "Expected gap to pass threshold");
        assertEquals("BULLISH", result.get(0).bias());
    }

    /**
     * AC2: Verify visual zone extension calculates end time correctly.
     */
    @Test
    void detect_withExtension_calculatesZoneEndTime() {
        // Setup: Create candles with 5-bar extension
        List<Candle> candles = List.of(
                candle(100, BD("100"), BD("102"), BD("100"), BD("101")),
                candle(200, BD("101"), BD("103"), BD("101"), BD("102")), // FVG formation bar (index 1)
                candle(300, BD("102.5"), BD("105"), BD("102.5"), BD("103")), // Gap forms here (index 2)
                candle(400, BD("103"), BD("106"), BD("103"), BD("104")), // Extension bar 1
                candle(500, BD("104"), BD("107"), BD("104"), BD("105")), // Extension bar 2
                candle(600, BD("105"), BD("108"), BD("105"), BD("106")), // Extension bar 3
                candle(700, BD("106"), BD("109"), BD("106"), BD("107"))  // Extension bar 4
        );

        // With 3-bar extension from gap candle (index 2 + 3 = index 5)
        FairValueGapDetector detector = new FairValueGapDetector(5, BD("0"), 3);
        List<FairValueGapDetector.FairValueGap> result = detector.detect(candles);

        // AC2: Zone extension end time should be epoch 600 (index 5)
        assertEquals(1, result.size());
        assertEquals(600L, result.get(0).extensionEndTime(), "Extension should end at bar index+extensionBars");
    }

    /**
     * AC3: Verify unmitigated FVGs are tracked properly.
     */
    @Test
    void detect_unmitigatedFVGs_areReturned() {
        // Setup: Create bullish FVG that is not mitigated
        List<Candle> candles = List.of(
                candle(100, BD("100"), BD("102"), BD("100"), BD("101")),
                candle(200, BD("101"), BD("103"), BD("101"), BD("102")), // FVG formation bar
                candle(300, BD("102.5"), BD("105"), BD("102.5"), BD("103")), // Bullish FVG forms (low 102.5 > prev2 high 102)
                candle(400, BD("103"), BD("106"), BD("103"), BD("104")), // Gap still open
                candle(500, BD("104"), BD("107"), BD("104"), BD("105"))  // Gap still unmitigated
        );

        FairValueGapDetector detector = new FairValueGapDetector(5, BD("0"), 0);
        List<FairValueGapDetector.FairValueGap> result = detector.detect(candles);

        // AC3: Unmitigated FVG should be returned
        assertEquals(1, result.size());
        assertEquals("BULLISH", result.get(0).bias());
        assertEquals(BD("102.5"), result.get(0).top());
        assertEquals(BD("102"), result.get(0).bottom());
    }

    /**
     * Verify mitigated FVGs are excluded from results.
     */
    @Test
    void detect_mitigatedFVGs_areExcluded() {
        // Setup: Create bullish FVG that gets mitigated
        List<Candle> candles = List.of(
                candle(100, BD("100"), BD("102"), BD("100"), BD("101")),
                candle(200, BD("101"), BD("103"), BD("101"), BD("102")), // FVG formation bar
                candle(300, BD("102.5"), BD("105"), BD("102.5"), BD("103")), // Bullish FVG forms
                candle(400, BD("98"), BD("106"), BD("98"), BD("104")), // Price enters gap (low 98 < 102), mitigates
                candle(500, BD("104"), BD("107"), BD("104"), BD("105"))
        );

        FairValueGapDetector detector = new FairValueGapDetector(5, BD("0"), 0);
        List<FairValueGapDetector.FairValueGap> result = detector.detect(candles);

        // FVG should be excluded because it's mitigated
        assertEquals(0, result.size(), "Mitigated FVGs should be excluded");
    }

    /**
     * Verify bearish FVG detection with threshold.
     * Bearish FVG: curr.high < prev2.low (gap forms below previous two candles).
     */
    @Test
    void detect_bearishFVG_withThreshold() {
        // Setup: Create bearish FVG (curr.high < prev2.low)
        List<Candle> candles = List.of(
                candle(100, BD("105"), BD("110"), BD("100"), BD("109")), // prev2: high=110, low=100
                candle(200, BD("108"), BD("110"), BD("105"), BD("106")), // formation bar
                candle(300, BD("90"), BD("99"), BD("90"), BD("95")) // curr: high=99 < prev2.low=100, forms gap from 100 to 99
        );

        // Gap size: 100 - 99 = 1.0, should pass 0.5 threshold
        FairValueGapDetector detector = new FairValueGapDetector(5, BD("0.5"), 0);
        List<FairValueGapDetector.FairValueGap> result = detector.detect(candles);

        // Bearish FVG should pass threshold
        assertEquals(1, result.size());
        assertEquals("BEARISH", result.get(0).bias());
        assertEquals(BD("100"), result.get(0).top());    // prev2.low
        assertEquals(BD("99"), result.get(0).bottom()); // curr.high
    }

    /**
     * Verify maxActive limit returns only most recent FVGs.
     */
    @Test
    void detect_maxActive_limitsResults() {
        // Setup: Create multiple FVGs
        List<Candle> candles = List.of(
                candle(100, BD("100"), BD("102"), BD("100"), BD("101")),
                candle(200, BD("101"), BD("103"), BD("101"), BD("102")),
                candle(300, BD("102.5"), BD("105"), BD("102.5"), BD("103")), // FVG 1
                candle(400, BD("103"), BD("105"), BD("103"), BD("104")),
                candle(500, BD("104.5"), BD("106"), BD("104.5"), BD("105")), // FVG 2
                candle(600, BD("105"), BD("107"), BD("105"), BD("106")),
                candle(700, BD("106.5"), BD("108"), BD("106.5"), BD("107"))  // FVG 3
        );

        // With maxActive=2, should only return 2 most recent FVGs
        FairValueGapDetector detector = new FairValueGapDetector(2, BD("0"), 0);
        List<FairValueGapDetector.FairValueGap> result = detector.detect(candles);

        // Should return only 2 most recent
        assertEquals(2, result.size(), "Should limit to maxActive=2");
    }

    /**
     * Verify no FVGs when candles < 3.
     */
    @Test
    void detect_insufficientCandles_returnsEmpty() {
        FairValueGapDetector detector = new FairValueGapDetector(5, BD("0"), 0);

        // < 3 candles
        List<FairValueGapDetector.FairValueGap> result = detector.detect(List.of(
                candle(100, BD("100"), BD("102"), BD("100"), BD("101")),
                candle(200, BD("101"), BD("103"), BD("101"), BD("102"))
        ));

        assertTrue(result.isEmpty());
    }

    private BigDecimal BD(String val) {
        return new BigDecimal(val);
    }
}
