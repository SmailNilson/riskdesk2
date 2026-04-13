package com.riskdesk.domain.engine.playbook;

import com.riskdesk.domain.engine.playbook.model.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaybookEvaluatorTest {

    private final PlaybookEvaluator evaluator = new PlaybookEvaluator();

    // ── Filter Tests ────────────────────────────────────────────────────

    @Test
    void filters_bullishBias_longDirection() {
        PlaybookInput input = minimalInput("BULLISH", bd("97.00"), null, null);
        FilterResult filters = evaluator.evaluateFilters(input);

        assertTrue(filters.biasAligned());
        assertEquals(Direction.LONG, filters.tradeDirection());
    }

    @Test
    void filters_bearishBias_shortDirection() {
        PlaybookInput input = minimalInput("BEARISH", bd("97.00"), null, null);
        FilterResult filters = evaluator.evaluateFilters(input);

        assertTrue(filters.biasAligned());
        assertEquals(Direction.SHORT, filters.tradeDirection());
    }

    @Test
    void filters_noBias_notAligned() {
        PlaybookInput input = minimalInput(null, bd("97.00"), null, null);
        FilterResult filters = evaluator.evaluateFilters(input);

        assertFalse(filters.biasAligned());
    }

    @Test
    void filters_belowVA_longOk() {
        PlaybookInput input = minimalInput("BULLISH", bd("95.00"), 100.0, 117.0);
        FilterResult filters = evaluator.evaluateFilters(input);

        assertEquals(VaPosition.BELOW_VA, filters.vaPosition());
        assertTrue(filters.vaPositionOk());
    }

    @Test
    void filters_aboveVA_longBlocked() {
        PlaybookInput input = minimalInput("BULLISH", bd("120.00"), 100.0, 117.0);
        FilterResult filters = evaluator.evaluateFilters(input);

        assertEquals(VaPosition.ABOVE_VA, filters.vaPosition());
        assertFalse(filters.vaPositionOk());
    }

    @Test
    void filters_fakeBreaks_reducedSize() {
        List<SmcBreak> breaks = List.of(
            new SmcBreak("CHOCH", "BULLISH", bd("97.23"), "INTERNAL", 0.40),
            new SmcBreak("CHOCH", "BULLISH", bd("98.74"), "INTERNAL", 0.90),
            new SmcBreak("BOS", "BULLISH", bd("98.34"), "INTERNAL", 0.50)
        );
        PlaybookInput input = inputWithBreaks("BULLISH", bd("97.00"), breaks);
        FilterResult filters = evaluator.evaluateFilters(input);

        assertFalse(filters.structureClean());
        assertEquals(0.5, filters.sizeMultiplier());
    }

    // ── Setup Detection Tests ────────────────────────────────────────────

    @Test
    void evaluate_detectsZoneRetest_bullishOB() {
        SmcOrderBlock ob = new SmcOrderBlock("BULLISH", "ACTIVE",
            bd("94.71"), bd("91.03"), bd("92.87"), null);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("93.00"),
            List.of(), List.of(ob), List.of(), List.of(), List.of(), List.of(),
            111.2, 100.0, 117.0, "BUYING", bd("0.65"), "DISCOUNT", null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNotNull(result.bestSetup());
        assertEquals(SetupType.ZONE_RETEST, result.bestSetup().type());
        assertTrue(result.bestSetup().priceInZone());
        assertNotNull(result.plan());
        assertTrue(result.plan().rrRatio() > 0);
    }

    @Test
    void evaluate_detectsBreaker() {
        SmcOrderBlock breaker = new SmcOrderBlock("BULLISH", "BREAKER",
            bd("97.11"), bd("96.69"), bd("96.90"), "BEARISH");

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("96.95"),
            List.of(), List.of(), List.of(breaker), List.of(), List.of(), List.of(),
            111.2, 100.0, 117.0, "BUYING", bd("0.65"), "DISCOUNT", null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNotNull(result.bestSetup());
        assertEquals(SetupType.ZONE_RETEST, result.bestSetup().type());
        assertTrue(result.bestSetup().zoneName().contains("Breaker"));
    }

    @Test
    void evaluate_noSetup_whenNoBias() {
        PlaybookInput input = minimalInput(null, bd("97.00"), null, null);
        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNull(result.bestSetup());
        assertNull(result.plan());
        assertTrue(result.verdict().contains("NO TRADE"));
    }

    @Test
    void evaluate_noSetup_whenNoZonesNearPrice() {
        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("97.00"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null, null, null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNull(result.bestSetup());
        assertTrue(result.verdict().contains("NO TRADE"));
    }

    @Test
    void evaluate_liquiditySweep_eqlNearPrice() {
        SmcEqualLevel eql = new SmcEqualLevel("EQL", bd("92.45"), 2);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("92.30"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(eql),
            null, null, null, "BUYING", bd("0.60"), "DISCOUNT", null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNotNull(result.bestSetup());
        assertEquals(SetupType.LIQUIDITY_SWEEP, result.bestSetup().type());
        assertTrue(result.bestSetup().zoneName().contains("EQL"));
    }

    @Test
    void evaluate_breakRetest_bosNearPrice() {
        SmcBreak bos = new SmcBreak("BOS", "BULLISH", bd("98.34"), "SWING", 0.85);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("98.40"),
            List.of(bos), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, null, null, "BUYING", bd("0.60"), null, null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNotNull(result.bestSetup());
        assertEquals(SetupType.BREAK_RETEST, result.bestSetup().type());
    }

    // ── Checklist Tests ─────────────────────────────────────────────────

    @Test
    void checklist_has7Items() {
        SmcOrderBlock ob = new SmcOrderBlock("BULLISH", "ACTIVE",
            bd("94.71"), bd("91.03"), bd("92.87"), null);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("93.00"),
            List.of(), List.of(ob), List.of(), List.of(), List.of(), List.of(),
            111.2, 100.0, 117.0, "BUYING", bd("0.65"), "DISCOUNT", null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNotNull(result.checklist());
        assertEquals(7, result.checklist().size());
        assertTrue(result.checklistScore() > 0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    private PlaybookInput minimalInput(String swingBias, BigDecimal lastPrice,
                                       Double vaLow, Double vaHigh) {
        return new PlaybookInput(
            swingBias, swingBias, bd("99.49"), bd("95.26"), lastPrice,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, vaLow, vaHigh, null, null, null, null, bd("1.50")
        );
    }

    private PlaybookInput inputWithBreaks(String swingBias, BigDecimal lastPrice,
                                          List<SmcBreak> breaks) {
        return new PlaybookInput(
            swingBias, swingBias, bd("99.49"), bd("95.26"), lastPrice,
            breaks, List.of(), List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null, null, null, bd("1.50")
        );
    }
}
