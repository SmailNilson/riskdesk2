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

class CmfDivergenceRuleTest {

    private CmfDivergenceRule rule;

    @BeforeEach
    void setUp() {
        rule = new CmfDivergenceRule();
    }

    @Test
    void bearishDivergence_higherPriceLowerCmf() {
        // Build 5-bar pattern: low - swing high 1 - dip - swing high 2 (higher price, lower CMF) - dip
        feed("100.00", "0.20", "2026-04-01T10:00:00Z");
        feed("102.00", "0.30", "2026-04-01T10:10:00Z"); // swing high 1: price=102, cmf=0.30
        feed("100.50", "0.15", "2026-04-01T10:20:00Z");
        feed("103.00", "0.20", "2026-04-01T10:30:00Z"); // swing high 2: price=103 > 102, cmf=0.20 < 0.30
        List<BehaviourAlertSignal> signals = feedAndReturn("101.00", "0.10", "2026-04-01T10:40:00Z");

        assertEquals(1, signals.size());
        BehaviourAlertSignal s = signals.get(0);
        assertEquals(BehaviourAlertCategory.CHAIKIN_BEHAVIOUR, s.category());
        assertTrue(s.message().contains("Bearish"));
    }

    @Test
    void bullishDivergence_lowerPriceHigherCmf() {
        // Build 5-bar pattern: high - swing low 1 - bounce - swing low 2 (lower price, higher CMF) - bounce
        feed("105.00", "-0.10", "2026-04-01T10:00:00Z");
        feed("100.00", "-0.30", "2026-04-01T10:10:00Z"); // swing low 1: price=100, cmf=-0.30
        feed("103.00", "-0.15", "2026-04-01T10:20:00Z");
        feed("99.00",  "-0.20", "2026-04-01T10:30:00Z");  // swing low 2: price=99 < 100, cmf=-0.20 > -0.30
        List<BehaviourAlertSignal> signals = feedAndReturn("102.00", "-0.10", "2026-04-01T10:40:00Z");

        assertEquals(1, signals.size());
        assertTrue(signals.get(0).message().contains("Bullish"));
    }

    @Test
    void noDivergence_alignedTrend() {
        // Price and CMF both rising — no divergence
        feed("100.00", "0.10", "2026-04-01T10:00:00Z");
        feed("102.00", "0.20", "2026-04-01T10:10:00Z");
        feed("101.00", "0.15", "2026-04-01T10:20:00Z");
        feed("103.00", "0.30", "2026-04-01T10:30:00Z"); // higher price AND higher CMF
        List<BehaviourAlertSignal> signals = feedAndReturn("101.50", "0.25", "2026-04-01T10:40:00Z");

        assertTrue(signals.isEmpty());
    }

    @Test
    void candleGuard_blocksNullCandle() {
        BehaviourAlertContext ctx = new BehaviourAlertContext(
                "MCL", "10m",
                new BigDecimal("100.00"), null, null,
                Collections.emptyList(), null,
                null, new BigDecimal("0.30")
        );
        assertTrue(rule.evaluate(ctx).isEmpty());
    }

    // ---- helpers ----

    private void feed(String price, String cmf, String timestamp) {
        rule.evaluate(context(price, cmf, timestamp));
    }

    private List<BehaviourAlertSignal> feedAndReturn(String price, String cmf, String timestamp) {
        return rule.evaluate(context(price, cmf, timestamp));
    }

    private static BehaviourAlertContext context(String price, String cmf, String timestamp) {
        return new BehaviourAlertContext(
                "MCL", "10m",
                new BigDecimal(price), null, null,
                Collections.emptyList(),
                Instant.parse(timestamp),
                null,
                new BigDecimal(cmf)
        );
    }
}
