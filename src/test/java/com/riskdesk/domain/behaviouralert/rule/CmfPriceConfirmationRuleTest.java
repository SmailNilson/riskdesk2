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

class CmfPriceConfirmationRuleTest {

    private static final Instant CANDLE = Instant.parse("2026-04-01T10:00:00Z");

    private CmfPriceConfirmationRule rule;

    @BeforeEach
    void setUp() {
        rule = new CmfPriceConfirmationRule();
    }

    @Test
    void bullishConfirmation_nearSupportWithPositiveCmf() {
        // Price = 100.10, STRONG_LOW = 100.05 → 0.05% < 0.15%, CMF = +0.12 → bullish
        var level = new SrLevel("STRONG_LOW", new BigDecimal("100.05"));
        BehaviourAlertContext ctx = context("100.10", "0.12", List.of(level));

        List<BehaviourAlertSignal> signals = rule.evaluate(ctx);

        assertEquals(1, signals.size());
        BehaviourAlertSignal s = signals.get(0);
        assertEquals(BehaviourAlertCategory.CHAIKIN_BEHAVIOUR, s.category());
        assertTrue(s.message().contains("Bullish"));
        assertTrue(s.message().contains("STRONG_LOW"));
    }

    @Test
    void bearishConfirmation_nearResistanceWithNegativeCmf() {
        // Price = 110.26, EQH = 110.28 → 0.018% < 0.15%, CMF = -0.15 → bearish
        var level = new SrLevel("EQH", new BigDecimal("110.28"));
        BehaviourAlertContext ctx = context("110.26", "-0.15", List.of(level));

        List<BehaviourAlertSignal> signals = rule.evaluate(ctx);

        assertEquals(1, signals.size());
        assertTrue(signals.get(0).message().contains("Bearish"));
        assertTrue(signals.get(0).message().contains("EQH"));
    }

    @Test
    void wrongCmfDirection_noConfirmation() {
        // Price near support but CMF negative → no confirmation
        var level = new SrLevel("STRONG_LOW", new BigDecimal("100.05"));
        BehaviourAlertContext ctx = context("100.10", "-0.05", List.of(level));

        assertTrue(rule.evaluate(ctx).isEmpty());
    }

    @Test
    void noProximity_noSignal() {
        // Price = 112.00, level = 110.28 → 1.5% > 0.15% → no proximity
        var level = new SrLevel("EQH", new BigDecimal("110.28"));
        BehaviourAlertContext ctx = context("112.00", "-0.20", List.of(level));

        assertTrue(rule.evaluate(ctx).isEmpty());
    }

    private static BehaviourAlertContext context(String price, String cmf, List<SrLevel> levels) {
        return new BehaviourAlertContext(
                "MCL", "10m",
                new BigDecimal(price),
                null, null,
                levels,
                CANDLE,
                null,
                new BigDecimal(cmf)
        );
    }
}
