package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.presentation.dto.IndicatorSnapshot;
import com.riskdesk.presentation.dto.IndicatorSnapshot.OrderBlockView;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndicatorAlertEvaluatorTest {

    private final IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator();

    /**
     * Helper to construct an IndicatorSnapshot with defaults for most fields.
     * Only the fields relevant to a specific test need non-null values.
     */
    private static IndicatorSnapshot makeSnapshot(
            String emaCrossover,
            BigDecimal rsi, String rsiSignal,
            String macdCrossover,
            String lastBreakType,
            BigDecimal vwap,
            List<OrderBlockView> activeOrderBlocks) {
        return new IndicatorSnapshot(
            "MCL", "10m",
            // EMAs
            null, null, null, emaCrossover,
            // RSI
            rsi, rsiSignal,
            // MACD
            null, null, null, macdCrossover,
            // Supertrend
            null, false,
            // VWAP
            vwap, null, null,
            // Chaikin
            null, null,
            // Bollinger Bands
            null, null, null, null, null,
            // BBTrend
            null, false, null,
            // Delta Flow Profile
            null, null, null, null,
            // WaveTrend
            null, null, null, null, null,
            // SMC Structure
            null, null, null, null, null, lastBreakType,
            // SMC timestamps
            null, null, null, null,
            // Order Blocks
            activeOrderBlocks == null ? Collections.emptyList() : activeOrderBlocks,
            Collections.emptyList(),
            Collections.emptyList()
        );
    }

    @Test
    void goldenCross_generatesEmaInfoAlert() {
        IndicatorSnapshot snap = makeSnapshot("GOLDEN_CROSS", null, null, null, null, null, null);

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
        IndicatorSnapshot snap = makeSnapshot("DEATH_CROSS", null, null, null, null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.WARNING, a.severity());
        assertEquals(AlertCategory.EMA, a.category());
        assertTrue(a.message().contains("Death Cross"));
    }

    @Test
    void rsiOversold_generatesRsiInfoAlert() {
        IndicatorSnapshot snap = makeSnapshot(null, new BigDecimal("25.3"), "OVERSOLD", null, null, null, null);

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
        IndicatorSnapshot snap = makeSnapshot(null, new BigDecimal("78.5"), "OVERBOUGHT", null, null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MNQ, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.INFO, a.severity());
        assertEquals(AlertCategory.RSI, a.category());
        assertTrue(a.message().contains("overbought"));
    }

    @Test
    void macdBullishCross_generatesMacdInfoAlert() {
        IndicatorSnapshot snap = makeSnapshot(null, null, null, "BULLISH_CROSS", null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MGC, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.INFO, a.severity());
        assertEquals(AlertCategory.MACD, a.category());
        assertTrue(a.message().contains("Bullish Cross"));
    }

    @Test
    void bosDetected_generatesSmcInfoAlert() {
        IndicatorSnapshot snap = makeSnapshot(null, null, null, null, "BOS_UP", null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "1h", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.INFO, a.severity());
        assertEquals(AlertCategory.SMC, a.category());
        assertTrue(a.message().contains("BOS"));
    }

    @Test
    void chochDetected_generatesSmcWarningAlert() {
        IndicatorSnapshot snap = makeSnapshot(null, null, null, null, "CHOCH_DOWN", null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.WARNING, a.severity());
        assertEquals(AlertCategory.SMC, a.category());
        assertTrue(a.message().contains("CHoCH"));
    }

    @Test
    void nullSnapshot_returnsEmptyList() {
        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", null);

        assertTrue(alerts.isEmpty());
    }

    @Test
    void noSignals_returnsEmptyList() {
        IndicatorSnapshot snap = makeSnapshot(null, null, null, null, null, null, null);

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertTrue(alerts.isEmpty());
    }

    @Test
    void orderBlockTouch_generatesOrderBlockAlert() {
        OrderBlockView ob = new OrderBlockView("BULLISH", new BigDecimal("62.50"), new BigDecimal("62.00"), new BigDecimal("62.25"), 0L);
        IndicatorSnapshot snap = makeSnapshot(null, null, null, null, null, new BigDecimal("62.30"), List.of(ob));

        List<Alert> alerts = evaluator.evaluate(Instrument.MCL, "10m", snap);

        assertEquals(1, alerts.size());
        Alert a = alerts.get(0);
        assertEquals(AlertSeverity.INFO, a.severity());
        assertEquals(AlertCategory.ORDER_BLOCK, a.category());
        assertTrue(a.message().contains("VWAP inside"));
    }
}
