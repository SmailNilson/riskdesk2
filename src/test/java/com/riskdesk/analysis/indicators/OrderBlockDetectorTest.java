package com.riskdesk.analysis.indicators;

import com.riskdesk.domain.engine.smc.OrderBlockDetector;
import com.riskdesk.domain.engine.smc.OrderBlockDetector.*;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderBlockDetectorTest {

    private static final Instant BASE_TIME = Instant.parse("2025-01-01T00:00:00Z");
    private OrderBlockDetector detector;

    @BeforeEach
    void setUp() {
        detector = new OrderBlockDetector(); // defaults: lookback=10, maxOrderBlocks=3, impulseFactor=0.5
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
     * Build a candle sequence that includes bullish OB patterns:
     * - Several neutral candles as background
     * - Then a bearish candle followed by two strong bullish candles (impulse)
     */
    private List<Candle> generateWithBullishOBPattern() {
        List<Candle> candles = new ArrayList<>();
        int idx = 0;

        // Background candles (need lookback=10 of them)
        for (int i = 0; i < 12; i++) {
            double base = 70.0 + i * 0.05;
            candles.add(candle(base, base + 0.2, base - 0.2, base + 0.05, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }

        // Bearish candle (the order block): open > close, clear bearish body
        candles.add(candle(70.60, 70.70, 70.30, 70.35, 1500,
                BASE_TIME.plusSeconds(600L * idx++)));

        // Strong bullish impulse candle 1: large body relative to range (body/range >= 0.5)
        candles.add(candle(70.40, 71.50, 70.35, 71.45, 3000,
                BASE_TIME.plusSeconds(600L * idx++)));

        // Bullish confirmation candle 2
        candles.add(candle(71.45, 72.30, 71.40, 72.25, 2500,
                BASE_TIME.plusSeconds(600L * idx++)));

        // A few more candles for the detector to have room (needs i+2 < size)
        for (int i = 0; i < 5; i++) {
            double base = 72.30 + i * 0.3;
            candles.add(candle(base, base + 0.2, base - 0.1, base + 0.15, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }

        return candles;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void detect_with30PlusCandles_returnsList() {
        List<Candle> candles = generateWithBullishOBPattern();

        List<OrderBlock> obs = detector.detect(candles);

        assertNotNull(obs, "Order block list should not be null");
        // The list can be empty or populated depending on impulse detection
    }

    @Test
    void eachOB_hasTypeHighLowAndStatus() {
        List<Candle> candles = generateWithBullishOBPattern();

        List<OrderBlock> obs = detector.detect(candles);

        for (OrderBlock ob : obs) {
            assertNotNull(ob.type(), "OB type should not be null");
            assertTrue(ob.type() == OBType.BULLISH || ob.type() == OBType.BEARISH,
                    "OB type should be BULLISH or BEARISH, was: " + ob.type());
            assertNotNull(ob.highPrice(), "OB high price should not be null");
            assertNotNull(ob.lowPrice(), "OB low price should not be null");
            assertTrue(ob.highPrice().compareTo(ob.lowPrice()) >= 0,
                    "OB high should be >= low. High=" + ob.highPrice() + ", Low=" + ob.lowPrice());
            assertNotNull(ob.status(), "OB status should not be null");
            assertTrue(ob.status() == OBStatus.ACTIVE
                            || ob.status() == OBStatus.MITIGATED
                            || ob.status() == OBStatus.BREAKER,
                    "OB status should be ACTIVE, MITIGATED, or BREAKER, was: " + ob.status());
        }
    }

    @Test
    void bullishOB_bearishCandleFollowedByBullishImpulse() {
        List<Candle> candles = generateWithBullishOBPattern();

        List<OrderBlock> obs = detector.detect(candles);

        // Filter for bullish OBs
        List<OrderBlock> bullishOBs = obs.stream()
                .filter(ob -> ob.type() == OBType.BULLISH)
                .toList();

        assertFalse(bullishOBs.isEmpty(),
                "Should detect at least one bullish OB from the pattern");

        for (OrderBlock ob : bullishOBs) {
            assertEquals(OBType.BULLISH, ob.type());
            // The formation candle should be bearish (open > close for the OB candle)
            // In the OB record: highPrice = candle open, lowPrice = candle low (for bullish OB)
            assertTrue(ob.formationIndex() >= 0, "Formation index should be non-negative");
        }
    }

    @Test
    void maxOrderBlockCap_isRespected() {
        // Create many OB patterns to test max cap (default maxOrderBlocks=3)
        OrderBlockDetector cappedDetector = new OrderBlockDetector(10, 2, 0.5);
        List<Candle> candles = new ArrayList<>();
        int idx = 0;

        // Create background
        for (int i = 0; i < 12; i++) {
            double base = 70.0 + i * 0.05;
            candles.add(candle(base, base + 0.2, base - 0.2, base + 0.05, 1000,
                    BASE_TIME.plusSeconds(600L * idx++)));
        }

        // Create multiple bullish OB patterns
        for (int p = 0; p < 5; p++) {
            double levelBase = 70.60 + p * 3.0;

            // Bearish candle (order block)
            candles.add(candle(levelBase, levelBase + 0.1, levelBase - 0.3, levelBase - 0.25, 1500,
                    BASE_TIME.plusSeconds(600L * idx++)));

            // Strong bullish impulse
            candles.add(candle(levelBase - 0.2, levelBase + 1.2, levelBase - 0.25, levelBase + 1.15, 3000,
                    BASE_TIME.plusSeconds(600L * idx++)));

            // Bullish confirmation
            candles.add(candle(levelBase + 1.15, levelBase + 2.0, levelBase + 1.10, levelBase + 1.95, 2500,
                    BASE_TIME.plusSeconds(600L * idx++)));

            // Some continuation candles
            for (int i = 0; i < 3; i++) {
                double b = levelBase + 2.0 + i * 0.3;
                candles.add(candle(b, b + 0.2, b - 0.1, b + 0.15, 1000,
                        BASE_TIME.plusSeconds(600L * idx++)));
            }
        }

        List<OrderBlock> obs = cappedDetector.detect(candles);

        // Count per type should be <= maxOrderBlocks (2 in this case)
        long bullishCount = obs.stream().filter(ob -> ob.type() == OBType.BULLISH).count();
        long bearishCount = obs.stream().filter(ob -> ob.type() == OBType.BEARISH).count();

        assertTrue(bullishCount <= 2,
                "Bullish OB count should be <= 2, was: " + bullishCount);
        assertTrue(bearishCount <= 2,
                "Bearish OB count should be <= 2, was: " + bearishCount);
    }

    @Test
    void insufficientData_returnsEmptyList() {
        // Needs lookback+3=13 candles minimum
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candles.add(candle(70.0 + i, 71.0 + i, 69.0 + i, 70.5 + i, 1000,
                    BASE_TIME.plusSeconds(600L * i)));
        }

        List<OrderBlock> obs = detector.detect(candles);

        assertNotNull(obs);
        assertTrue(obs.isEmpty(),
                "Order blocks should be empty for insufficient data (5 < 13)");
    }

    @Test
    void midPoint_calculatedCorrectly() {
        // Verify the midPoint() helper on OrderBlock record
        List<Candle> candles = generateWithBullishOBPattern();

        List<OrderBlock> obs = detector.detect(candles);

        for (OrderBlock ob : obs) {
            BigDecimal expectedMid = ob.highPrice().add(ob.lowPrice())
                    .divide(BigDecimal.TWO, 5, java.math.RoundingMode.HALF_UP);
            assertEquals(0, expectedMid.compareTo(ob.midPoint()),
                    "Midpoint should be (high + low) / 2. Expected=" + expectedMid + ", Got=" + ob.midPoint());
        }
    }

    @Test
    void detect_activeOBs_areReturnedByDefault() {
        List<Candle> candles = generateWithBullishOBPattern();

        List<OrderBlock> obs = detector.detect(candles);

        // All returned OBs should be ACTIVE (mitigated ones are filtered out by detect())
        for (OrderBlock ob : obs) {
            assertEquals(OBStatus.ACTIVE, ob.status(),
                    "Returned OBs from detect() should be ACTIVE, was: " + ob.status());
        }
    }

    // ── UC-SMC-009: lifecycle event tests ────────────────────────────────────

    /**
     * AC1: Mitigation event fires when the last bar enters a bullish OB (low touches zone)
     * but the candle does NOT close below the low — demand zone tested, not broken.
     */
    @Test
    void detectWithEvents_bullishOBMitigation_onLastBar() {
        // Build: background + OB formation + continuation + last bar that touches OB
        List<Candle> candles = new ArrayList<>();
        int idx = 0;

        // Background (lookback = 10)
        for (int i = 0; i < 12; i++) {
            double b = 100.0 + i * 0.1;
            candles.add(candle(b, b + 0.2, b - 0.2, b + 0.05, 1000, BASE_TIME.plusSeconds(600L * idx++)));
        }

        // Bearish OB candle: open=101.50, close=101.10 (bearish)
        candles.add(candle(101.50, 101.60, 101.05, 101.10, 2000, BASE_TIME.plusSeconds(600L * idx++)));

        // Bullish impulse: body >= 50% of range, bullish
        candles.add(candle(101.10, 102.50, 101.00, 102.40, 5000, BASE_TIME.plusSeconds(600L * idx++)));

        // Bullish confirmation
        candles.add(candle(102.40, 103.50, 102.30, 103.40, 4000, BASE_TIME.plusSeconds(600L * idx++)));

        // Continuation candles — price stays above OB
        for (int i = 0; i < 5; i++) {
            double b = 103.50 + i * 0.2;
            candles.add(candle(b, b + 0.3, b - 0.1, b + 0.2, 1000, BASE_TIME.plusSeconds(600L * idx++)));
        }

        // Last bar: low touches bullish OB (low <= ob.high = 101.50), but close > ob.low (101.05)
        // → mitigation (zone tested, demand held)
        candles.add(candle(104.50, 104.60, 101.20, 102.80, 3000, BASE_TIME.plusSeconds(600L * idx)));

        OrderBlockDetector.DetectionResult result = detector.detectWithEvents(candles);

        boolean hasMitigation = result.events().stream()
                .anyMatch(e -> e.eventType() == OBEventType.MITIGATION && e.obType() == OBType.BULLISH);
        assertTrue(hasMitigation, "Expected a BULLISH MITIGATION event when last bar touches OB zone without closing below it. Events: " + result.events());
    }

    /**
     * AC2: Invalidation event fires when the last bar closes below the entire bullish OB zone
     * (close < ob.low) — demand failed, structural breakdown.
     */
    @Test
    void detectWithEvents_bullishOBInvalidation_onLastBar() {
        List<Candle> candles = new ArrayList<>();
        int idx = 0;

        // Background
        for (int i = 0; i < 12; i++) {
            double b = 100.0 + i * 0.1;
            candles.add(candle(b, b + 0.2, b - 0.2, b + 0.05, 1000, BASE_TIME.plusSeconds(600L * idx++)));
        }

        // Bearish OB candle: high=101.60, low=101.05, open=101.50, close=101.10
        candles.add(candle(101.50, 101.60, 101.05, 101.10, 2000, BASE_TIME.plusSeconds(600L * idx++)));

        // Bullish impulse
        candles.add(candle(101.10, 102.50, 101.00, 102.40, 5000, BASE_TIME.plusSeconds(600L * idx++)));

        // Bullish confirmation
        candles.add(candle(102.40, 103.50, 102.30, 103.40, 4000, BASE_TIME.plusSeconds(600L * idx++)));

        // Continuation above OB
        for (int i = 0; i < 5; i++) {
            double b = 103.50 + i * 0.2;
            candles.add(candle(b, b + 0.3, b - 0.1, b + 0.2, 1000, BASE_TIME.plusSeconds(600L * idx++)));
        }

        // Last bar: low enters OB (low <= ob.high = 101.50) AND close < ob.low (101.05) → invalidation
        candles.add(candle(104.00, 104.10, 100.80, 100.90, 8000, BASE_TIME.plusSeconds(600L * idx)));

        OrderBlockDetector.DetectionResult result = detector.detectWithEvents(candles);

        boolean hasInvalidation = result.events().stream()
                .anyMatch(e -> e.eventType() == OBEventType.INVALIDATION && e.obType() == OBType.BULLISH);
        assertTrue(hasInvalidation, "Expected a BULLISH INVALIDATION event when last bar closes below OB low. Events: " + result.events());
        assertTrue(result.breakerOrderBlocks().stream().anyMatch(ob -> ob.status() == OBStatus.BREAKER && ob.type() == OBType.BEARISH),
                "Expected invalidated bullish OB to surface as a bearish breaker block (V2). Breakers: " + result.breakerOrderBlocks());
    }
}
