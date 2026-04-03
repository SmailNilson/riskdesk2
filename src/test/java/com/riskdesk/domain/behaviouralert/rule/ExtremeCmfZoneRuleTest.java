package com.riskdesk.domain.behaviouralert.rule;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertCategory;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtremeCmfZoneRuleTest {

    private static final Instant CANDLE_1 = Instant.parse("2026-04-01T10:00:00Z");
    private static final Instant CANDLE_2 = Instant.parse("2026-04-01T10:10:00Z");

    private ExtremeCmfZoneRule rule;

    @BeforeEach
    void setUp() {
        rule = new ExtremeCmfZoneRule();
    }

    @Test
    void accumulationTransition_firesSignal() {
        BehaviourAlertContext ctx = context("0.32", CANDLE_1);

        List<BehaviourAlertSignal> signals = rule.evaluate(ctx);

        assertEquals(1, signals.size());
        BehaviourAlertSignal s = signals.get(0);
        assertEquals(BehaviourAlertCategory.CHAIKIN_BEHAVIOUR, s.category());
        assertTrue(s.message().contains("accumulation"));
        assertTrue(s.message().contains("0.32"));
    }

    @Test
    void distributionTransition_firesSignal() {
        BehaviourAlertContext ctx = context("-0.35", CANDLE_1);

        List<BehaviourAlertSignal> signals = rule.evaluate(ctx);

        assertEquals(1, signals.size());
        assertTrue(signals.get(0).message().contains("distribution"));
        assertTrue(signals.get(0).message().contains("-0.35"));
    }

    @Test
    void neutralZone_noSignal() {
        BehaviourAlertContext ctx = context("0.10", CANDLE_1);
        assertTrue(rule.evaluate(ctx).isEmpty());
    }

    @Test
    void noRefireOnSameCandle() {
        BehaviourAlertContext ctx = context("0.30", CANDLE_1);
        rule.evaluate(ctx);

        // Reset to neutral then back to accumulation on same candle
        rule.evaluate(context("0.05", CANDLE_1));
        assertTrue(rule.evaluate(context("0.30", CANDLE_1)).isEmpty());

        // New candle allows re-fire
        assertEquals(1, rule.evaluate(context("0.30", CANDLE_2)).size());
    }

    private static BehaviourAlertContext context(String cmf, Instant candle) {
        return new BehaviourAlertContext(
                "MCL", "10m",
                new BigDecimal("100.00"),
                null, null,
                Collections.emptyList(),
                candle,
                null,
                new BigDecimal(cmf)
        );
    }
}
