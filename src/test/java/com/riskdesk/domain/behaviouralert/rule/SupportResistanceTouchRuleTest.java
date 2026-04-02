package com.riskdesk.domain.behaviouralert.rule;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertCategory;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext.SrLevel;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SupportResistanceTouchRuleTest {

    private static final Instant CLOSED_CANDLE = Instant.parse("2026-03-28T16:00:00Z");
    private static final Instant NEXT_CANDLE   = Instant.parse("2026-03-28T16:10:00Z");

    private SupportResistanceTouchRule rule;

    @BeforeEach
    void setUp() {
        rule = new SupportResistanceTouchRule();
    }

    @Test
    void nearTransition_firesWithLevelTypeInMessage() {
        // price = 110.26, STRONG_HIGH = 110.28 → 0.018% < 0.15% → NEAR
        var level = new SrLevel("STRONG_HIGH", new BigDecimal("110.28"));
        BehaviourAlertContext ctx = context("110.26", List.of(level), CLOSED_CANDLE);

        List<BehaviourAlertSignal> signals = rule.evaluate(ctx);

        assertEquals(1, signals.size());
        BehaviourAlertSignal s = signals.get(0);
        assertEquals(BehaviourAlertCategory.SUPPORT_RESISTANCE, s.category());
        assertTrue(s.message().contains("STRONG_HIGH"), "Message must contain level type");
        assertTrue(s.message().contains("110.28"),      "Message must contain level price");
    }

    @Test
    void farState_noSignal() {
        // price = 112.00, level = 110.28 → 1.5% > 0.15% → FAR
        var level = new SrLevel("EQH", new BigDecimal("110.28"));
        BehaviourAlertContext ctx = context("112.00", List.of(level), CLOSED_CANDLE);
        assertTrue(rule.evaluate(ctx).isEmpty());
    }

    @Test
    void multipleLevelsNear_fireIndependently() {
        var levels = List.of(
                new SrLevel("EQH",         new BigDecimal("100.05")),
                new SrLevel("STRONG_HIGH", new BigDecimal("100.08"))
        );
        BehaviourAlertContext ctx = context("100.10", levels, CLOSED_CANDLE);

        List<BehaviourAlertSignal> signals = rule.evaluate(ctx);

        assertEquals(2, signals.size());
        assertTrue(signals.stream().anyMatch(s -> s.message().contains("EQH")));
        assertTrue(signals.stream().anyMatch(s -> s.message().contains("STRONG_HIGH")));
    }

    @Test
    void guardOpenCandle_fireClosed() {
        var level = new SrLevel("EQL", new BigDecimal("100.05"));
        BehaviourAlertContext openCtx   = context("100.07", List.of(level), null);
        BehaviourAlertContext closedCtx = context("100.07", List.of(level), CLOSED_CANDLE);

        assertTrue(rule.evaluate(openCtx).isEmpty(), "Must not fire on open candle");
        assertEquals(1, rule.evaluate(closedCtx).size(), "Must fire after candle closes");
    }

    @Test
    void noRefireOnSameCandle() {
        var level = new SrLevel("WEAK_LOW", new BigDecimal("110.28"));
        BehaviourAlertContext ctx = context("110.29", List.of(level), CLOSED_CANDLE);

        rule.evaluate(ctx);
        assertTrue(rule.evaluate(ctx).isEmpty(), "Must not re-fire on same candle");
    }

    @Test
    void emptyLevelList_noSignal() {
        BehaviourAlertContext ctx = context("110.28", Collections.emptyList(), CLOSED_CANDLE);
        assertTrue(rule.evaluate(ctx).isEmpty());
    }

    // ---- helpers ----

    private static BehaviourAlertContext context(String price, List<SrLevel> srLevels, Instant candle) {
        return new BehaviourAlertContext(
                "MCL", "10m",
                new BigDecimal(price),
                null, null,
                srLevels,
                candle
        );
    }
}
