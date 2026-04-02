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

class Ema200ProximityRuleTest {

    private static final Instant CLOSED_CANDLE = Instant.parse("2026-03-28T16:00:00Z");
    private static final Instant NEXT_CANDLE   = Instant.parse("2026-03-28T16:10:00Z");

    private Ema200ProximityRule rule;

    @BeforeEach
    void setUp() {
        rule = new Ema200ProximityRule();
    }

    @Test
    void nearTransition_firesSignalWithEma200InMessage() {
        // price = 100.10, ema200 = 100.00 → 0.10% < 0.15% → NEAR
        BehaviourAlertContext ctx = context("100.10", "95.00", "100.00", CLOSED_CANDLE);

        List<BehaviourAlertSignal> signals = rule.evaluate(ctx);

        assertEquals(1, signals.size());
        BehaviourAlertSignal s = signals.get(0);
        assertEquals(BehaviourAlertCategory.EMA_PROXIMITY, s.category());
        assertTrue(s.message().contains("EMA200"), "Message must reference EMA200");
        assertFalse(s.message().contains("EMA50"),  "Message must not reference EMA50");
    }

    @Test
    void independentFromEma50State() {
        // EMA50 is FAR (3%), EMA200 is NEAR (0.10%) → only EMA200 rule fires
        Ema50ProximityRule ema50Rule = new Ema50ProximityRule();
        BehaviourAlertContext ctx = context("100.10", "103.00", "100.00", CLOSED_CANDLE);

        assertTrue(ema50Rule.evaluate(ctx).isEmpty(), "EMA50 must be FAR");
        assertEquals(1, rule.evaluate(ctx).size(), "EMA200 must fire independently");
    }

    @Test
    void farState_noSignal() {
        BehaviourAlertContext ctx = context("100.10", "95.00", "103.00", CLOSED_CANDLE);
        assertTrue(rule.evaluate(ctx).isEmpty());
    }

    @Test
    void noRefireOnSameCandle() {
        BehaviourAlertContext ctx = context("100.10", "95.00", "100.00", CLOSED_CANDLE);
        rule.evaluate(ctx);
        assertTrue(rule.evaluate(ctx).isEmpty());
    }

    // ---- helpers ----

    private static BehaviourAlertContext context(String price, String ema50, String ema200, Instant candle) {
        return new BehaviourAlertContext(
                "MNQ", "1h",
                price  != null ? new BigDecimal(price)  : null,
                ema50  != null ? new BigDecimal(ema50)  : null,
                ema200 != null ? new BigDecimal(ema200) : null,
                Collections.emptyList(),
                candle
        );
    }
}
