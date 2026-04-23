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

    @Test
    void evaluate_breakRetest_chochNearPrice() {
        // PR-9: CHoCH retests must be accepted by the evaluator too — this is
        // often the cleanest R:R entry into a freshly flipped trend.
        SmcBreak choch = new SmcBreak("CHOCH", "BULLISH", bd("98.34"), "SWING", 0.85);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("98.40"),
            List.of(choch), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, null, null, "BUYING", bd("0.60"), null, null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNotNull(result.bestSetup());
        assertEquals(SetupType.BREAK_RETEST, result.bestSetup().type());
        assertTrue(result.bestSetup().zoneName().contains("CHoCH"),
            "Zone name should surface the CHoCH flavour for audit: " + result.bestSetup().zoneName());
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

    @Test
    void checklist_rrItem_reflectsPlanRrRatio_notStaleSetupDefault() {
        // Regression: before the fix, ChecklistItem 7 ("R:R >= 2:1") read
        // setup.rrRatio() which was still 0.0 at the time buildChecklist() ran,
        // so the UI showed "R:R 0.0:1" while the mechanical plan displayed the
        // real ratio. The checklist must now surface the plan-derived ratio.
        SmcOrderBlock ob = new SmcOrderBlock("BULLISH", "ACTIVE",
            bd("94.71"), bd("91.03"), bd("92.87"), null);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("93.00"),
            List.of(), List.of(ob), List.of(), List.of(), List.of(), List.of(),
            111.2, 100.0, 117.0, "BUYING", bd("0.65"), "DISCOUNT", null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNotNull(result.plan(), "Plan must be computed for this setup");
        double planRr = result.plan().rrRatio();
        assertTrue(planRr > 0, "Sanity check: plan R:R should be > 0");

        ChecklistItem rrItem = result.checklist().stream()
            .filter(c -> c.step() == 7)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Checklist item 7 (R:R) missing"));

        String expected = String.format("R:R %.1f:1", planRr);
        assertEquals(expected, rrItem.detail(),
            "Checklist item 7 must reflect the plan R:R, not the stale setup default");
        assertNotEquals("R:R 0.0:1", rrItem.detail(),
            "Regression guard: must never display the stale 0.0 default");
    }

    @Test
    void checklist_orderFlow_longWithSellingDelta_labelsAsCapitulation() {
        // When a LONG setup is confirmed by SELLING delta (demand absorption
        // at a support zone), the checklist must label the flow as
        // "absorption/capitulation for LONG" so reviewers don't misread it as
        // a contradiction.
        SmcOrderBlock ob = new SmcOrderBlock("BULLISH", "ACTIVE",
            bd("94.71"), bd("91.03"), bd("92.87"), null);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("93.00"),
            List.of(), List.of(ob), List.of(), List.of(), List.of(), List.of(),
            111.2, 100.0, 117.0, "SELLING", bd("0.65"), "DISCOUNT", null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        ChecklistItem ofItem = result.checklist().stream()
            .filter(c -> c.step() == 6)
            .findFirst().orElseThrow();

        assertTrue(ofItem.detail().contains("SELLING"),
            "Delta bias should be surfaced in the label: " + ofItem.detail());
        assertTrue(ofItem.detail().contains("absorption/capitulation for LONG"),
            "SELLING delta on a LONG setup should be tagged as capitulation: " + ofItem.detail());
    }

    @Test
    void checklist_orderFlow_longWithBuyingDelta_labelsAsSupports() {
        SmcOrderBlock ob = new SmcOrderBlock("BULLISH", "ACTIVE",
            bd("94.71"), bd("91.03"), bd("92.87"), null);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("93.00"),
            List.of(), List.of(ob), List.of(), List.of(), List.of(), List.of(),
            111.2, 100.0, 117.0, "BUYING", bd("0.65"), "DISCOUNT", null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        ChecklistItem ofItem = result.checklist().stream()
            .filter(c -> c.step() == 6)
            .findFirst().orElseThrow();

        assertTrue(ofItem.detail().contains("supports LONG"),
            "BUYING delta on a LONG setup should be tagged as supports: " + ofItem.detail());
    }

    // ── PR-8 · Late-entry detection ─────────────────────────────────────

    @Test
    void evaluate_flagsLateEntry_whenPriceAdvancedBeyondEntry() {
        // Setup: bullish OB at 91.03–94.71 (zoneMid = 92.87 = planned entry).
        // lastPrice = 94.00 → price has advanced 1.13 past entry on a 1.50 ATR.
        // Tolerance = 0.5 × 1.50 = 0.75. 1.13 > 0.75 → LATE.
        SmcOrderBlock ob = new SmcOrderBlock("BULLISH", "ACTIVE",
            bd("94.71"), bd("91.03"), bd("92.87"), null);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("94.00"),
            List.of(), List.of(ob), List.of(), List.of(), List.of(), List.of(),
            111.2, 100.0, 117.0, "BUYING", bd("0.65"), "DISCOUNT", null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNotNull(result.bestSetup());
        assertTrue(result.lateEntry(),
            "Price has advanced past entry by more than 0.5×ATR — should flag late");
        assertTrue(result.verdict().startsWith("LATE ENTRY — "),
            "Verdict must surface the late-entry warning as a prefix: " + result.verdict());
    }

    @Test
    void evaluate_doesNotFlagLateEntry_whenPriceInZone() {
        // lastPrice = 93.00 is already inside the zone, near entry (92.87).
        // Advance = 0.13 << 0.75 tolerance → NOT late.
        SmcOrderBlock ob = new SmcOrderBlock("BULLISH", "ACTIVE",
            bd("94.71"), bd("91.03"), bd("92.87"), null);

        PlaybookInput input = new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("93.00"),
            List.of(), List.of(ob), List.of(), List.of(), List.of(), List.of(),
            111.2, 100.0, 117.0, "BUYING", bd("0.65"), "DISCOUNT", null, bd("1.50")
        );

        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNotNull(result.bestSetup());
        assertFalse(result.lateEntry(),
            "Price is at entry — fresh setup, not late");
        assertFalse(result.verdict().startsWith("LATE ENTRY"),
            "Verdict should not carry the late-entry prefix: " + result.verdict());
    }

    @Test
    void evaluate_noSetup_isNotFlaggedLate() {
        PlaybookInput input = minimalInput(null, bd("97.00"), null, null);
        PlaybookEvaluation result = evaluator.evaluate(input);

        assertNull(result.bestSetup());
        assertFalse(result.lateEntry(),
            "No setup → no late-entry flag (nothing to be late on)");
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
