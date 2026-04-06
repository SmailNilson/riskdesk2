package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.IndicatorAlertSnapshot;
import com.riskdesk.domain.alert.model.IndicatorAlertSnapshot.OrderBlockZone;
import com.riskdesk.domain.alert.model.IndicatorAlertSnapshot.OrderBlockEvent;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndicatorAlertEvaluatorTest {

    private static final Instant CLOSED_CANDLE = Instant.parse("2026-03-28T16:00:00Z");
    private static final Instant NEXT_CANDLE = Instant.parse("2026-03-28T17:00:00Z");
    private final IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator();

    /**
     * Helper to construct a snapshot with defaults. Only pass what the test actually needs.
     */
    private static IndicatorAlertSnapshot makeSnapshot(
            String emaCrossover,
            BigDecimal rsi, String rsiSignal,
            String macdCrossover,
            String lastBreakType,
            BigDecimal vwap,
            List<OrderBlockZone> activeOrderBlocks) {
        return makeSnapshotWithEvents(emaCrossover, rsi, rsiSignal, macdCrossover,
                lastBreakType, vwap, activeOrderBlocks, Collections.emptyList(), CLOSED_CANDLE);
    }

    private static IndicatorAlertSnapshot makeSnapshotWithEvents(
            String emaCrossover,
            BigDecimal rsi, String rsiSignal,
            String macdCrossover,
            String lastBreakType,
            BigDecimal vwap,
            List<OrderBlockZone> activeOrderBlocks,
            List<OrderBlockEvent> obEvents) {
        return makeSnapshotWithEvents(emaCrossover, rsi, rsiSignal, macdCrossover,
                lastBreakType, vwap, activeOrderBlocks, obEvents, CLOSED_CANDLE);
    }

    private static IndicatorAlertSnapshot makeSnapshotWithEvents(
            String emaCrossover,
            BigDecimal rsi, String rsiSignal,
            String macdCrossover,
            String lastBreakType,
            BigDecimal vwap,
            List<OrderBlockZone> activeOrderBlocks,
            List<OrderBlockEvent> obEvents,
            Instant lastCandleTimestamp) {
        // Derive a close price from the first OB event (if any) for proximity filter
        BigDecimal close = null;
        if (obEvents != null && !obEvents.isEmpty()) {
            OrderBlockEvent evt = obEvents.get(0);
            close = evt.high().add(evt.low()).divide(BigDecimal.TWO, 5, java.math.RoundingMode.HALF_UP);
        }
        return new IndicatorAlertSnapshot(
            emaCrossover,
            rsi, rsiSignal,
            macdCrossover,
            lastBreakType,
            lastBreakType,  // lastInternalBreakType (same for test simplicity)
            null,           // lastSwingBreakType
            null,
            null,
            null,
            vwap,
            activeOrderBlocks == null ? Collections.emptyList() : activeOrderBlocks,
            obEvents == null ? Collections.emptyList() : obEvents,
            lastCandleTimestamp,
            null, null, close, null, Collections.emptyList(), Collections.emptyList(),
            null, null, null, null, null, null, null, null
        );
    }

    private static IndicatorAlertSnapshot makeWaveTrendSnapshot(
            BigDecimal wtWt1,
            String wtCrossover,
            String wtSignal,
            Instant lastCandleTimestamp) {
        return new IndicatorAlertSnapshot(
                null, null, null, null, null, null, null,
                wtWt1, wtCrossover, wtSignal,
                null, Collections.emptyList(), Collections.emptyList(),
                lastCandleTimestamp,
                null, null, null, null, Collections.emptyList(), Collections.emptyList(),
                null, null, null, null, null, null, null, null
        );
    }

    @Test
    void goldenCross_generatesEmaInfoAlert() {
        IndicatorAlertSnapshot snap = makeSnapshot("GOLDEN_CROSS", null, null, null, null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.INFO, a.severity());
        assertEquals(AlertCategory.EMA, a.category());
        assertTrue(a.key().startsWith("ema:golden:"));
        assertTrue(a.message().contains("Golden Cross"));
    }

    @Test
    void deathCross_generatesEmaWarningAlert() {
        IndicatorAlertSnapshot snap = makeSnapshot("DEATH_CROSS", null, null, null, null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.WARNING, a.severity());
        assertEquals(AlertCategory.EMA, a.category());
        assertTrue(a.message().contains("Death Cross"));
    }

    @Test
    void rsiOversold_generatesRsiInfoAlert() {
        IndicatorAlertSnapshot snap = makeSnapshot(null, new BigDecimal("25.3"), "OVERSOLD", null, null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.E6, "1h", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.INFO, a.severity());
        assertEquals(AlertCategory.RSI, a.category());
        assertTrue(a.message().contains("oversold"));
        assertEquals("E6", a.instrument());
    }

    @Test
    void rsiOverbought_generatesRsiInfoAlert() {
        IndicatorAlertSnapshot snap = makeSnapshot(null, new BigDecimal("78.5"), "OVERBOUGHT", null, null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MNQ, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.INFO, a.severity());
        assertEquals(AlertCategory.RSI, a.category());
        assertTrue(a.message().contains("overbought"));
    }

    @Test
    void macdBullishCross_generatesMacdInfoAlert() {
        IndicatorAlertSnapshot snap = makeSnapshot(null, null, null, "BULLISH_CROSS", null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MGC, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.INFO, a.severity());
        assertEquals(AlertCategory.MACD, a.category());
        assertTrue(a.message().contains("Bullish Cross"));
    }

    @Test
    void bosDetected_generatesSmcInfoAlert() {
        IndicatorAlertSnapshot snap = makeSnapshot(null, null, null, null, "BOS_UP", null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "1h", snap);

        // Internal BOS + legacy BOS = 2 alerts
        assertEquals(2, alerts.size());
        assertTrue(alerts.stream().anyMatch(a ->
                a.severity() == AlertSeverity.INFO && a.category() == AlertCategory.SMC
                        && a.key().startsWith("smc:internal:bos:") && a.message().contains("Internal BOS")));
        assertTrue(alerts.stream().anyMatch(a ->
                a.severity() == AlertSeverity.INFO && a.category() == AlertCategory.SMC
                        && a.key().startsWith("smc:bos:") && a.message().contains("BOS")));
    }

    @Test
    void chochDetected_generatesSmcWarningAlert() {
        IndicatorAlertSnapshot snap = makeSnapshot(null, null, null, null, "CHOCH_DOWN", null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        // Internal CHoCH + legacy CHoCH = 2 alerts
        assertEquals(2, alerts.size());
        assertTrue(alerts.stream().anyMatch(a ->
                a.severity() == AlertSeverity.WARNING && a.category() == AlertCategory.SMC
                        && a.key().startsWith("smc:internal:choch:") && a.message().contains("Internal CHoCH")));
        assertTrue(alerts.stream().anyMatch(a ->
                a.severity() == AlertSeverity.WARNING && a.category() == AlertCategory.SMC
                        && a.key().startsWith("smc:choch:") && a.message().contains("CHoCH")));
    }

    @Test
    void nullSnapshot_returnsEmptyList() {
        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", null);

        assertTrue(alerts.isEmpty());
    }

    @Test
    void noSignals_returnsEmptyList() {
        IndicatorAlertSnapshot snap = makeSnapshot(null, null, null, null, null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertTrue(alerts.isEmpty());
    }

    // ── UC-SMC-009 acceptance criteria ────────────────────────────────────────

    /** AC1: alert fires on OB mitigation (INFO — demand zone tested, potential entry). */
    @Test
    void obMitigation_generatesInfoAlert() {
        OrderBlockEvent mitigation = new OrderBlockEvent(
                "MITIGATION", "BULLISH",
                new BigDecimal("62.50"), new BigDecimal("62.00"));

        IndicatorAlertSnapshot snap = makeSnapshotWithEvents(
                null, null, null, null, null, null, null, List.of(mitigation));

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.INFO, a.severity());
        assertEquals(AlertCategory.ORDER_BLOCK, a.category());
        assertTrue(a.message().contains("BULLISH"), "message should contain OB type");
        assertTrue(a.message().contains("mitigated"), "message should say 'mitigated'");
    }

    /** AC2: alert fires on OB invalidation (WARNING — zone broken, structural failure). */
    @Test
    void obInvalidation_generatesWarningAlert() {
        OrderBlockEvent invalidation = new OrderBlockEvent(
                "INVALIDATION", "BEARISH",
                new BigDecimal("65.00"), new BigDecimal("64.50"));

        IndicatorAlertSnapshot snap = makeSnapshotWithEvents(
                null, null, null, null, null, null, null, List.of(invalidation));

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.WARNING, a.severity());
        assertEquals(AlertCategory.ORDER_BLOCK, a.category());
        assertTrue(a.message().contains("BEARISH"), "message should contain OB type");
        assertTrue(a.message().contains("invalidated"), "message should say 'invalidated'");
    }

    /** No false positives: VWAP inside OB zone alone must NOT generate an alert. */
    @Test
    void vwapInsideOb_noAlert_withoutObEvent() {
        OrderBlockZone ob = new OrderBlockZone("BULLISH", new BigDecimal("62.50"), new BigDecimal("62.00"));
        // VWAP inside OB zone — old proxy behaviour that should no longer fire
        IndicatorAlertSnapshot snap = makeSnapshot(
                null, null, null, null, null, new BigDecimal("62.30"), List.of(ob));

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertTrue(alerts.isEmpty(), "VWAP-inside-OB must no longer generate an alert on its own");
    }

    @Test
    void smcTransition_isNotConsumedBeforeCandleClose() {
        IndicatorAlertSnapshot openCandleSnapshot = makeSnapshotWithEvents(
                null, null, null, null, "BOS_UP", null, null, Collections.emptyList(), null);
        IndicatorAlertSnapshot closedCandleSnapshot = makeSnapshotWithEvents(
                null, null, null, null, "BOS_UP", null, null, Collections.emptyList(), CLOSED_CANDLE);

        assertTrue(evaluator.evaluate(Instrument.MCL, "10m", openCandleSnapshot).isEmpty());

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", closedCandleSnapshot);

        assertEquals(2, alerts.size(), "confirmed BOS should still fire after the open-candle pass");
    }

    @Test
    void waveTrendTransition_isNotConsumedBeforeCandleClose() {
        IndicatorAlertSnapshot openCandleSnapshot = makeWaveTrendSnapshot(
                new BigDecimal("61.2"), null, "OVERBOUGHT", null);
        IndicatorAlertSnapshot closedCandleSnapshot = makeWaveTrendSnapshot(
                new BigDecimal("61.2"), null, "OVERBOUGHT", NEXT_CANDLE);

        assertTrue(evaluator.evaluate(Instrument.MNQ, "1h", openCandleSnapshot).isEmpty());

        List<Alert> alerts = evaluator.evaluate(Instrument.MNQ, "1h", closedCandleSnapshot);

        assertEquals(1, alerts.size(), "confirmed WaveTrend signal should still fire after the open-candle pass");
        assertTrue(alerts.get(0).message().contains("overbought"));
    }
}
