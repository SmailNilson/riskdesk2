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

class Ema50ProximityRuleTest {

    private static final Instant CLOSED_CANDLE = Instant.parse("2026-03-28T16:00:00Z");
    private static final Instant NEXT_CANDLE   = Instant.parse("2026-03-28T16:10:00Z");

    private Ema50ProximityRule rule;

    @BeforeEach
    void setUp() {
        rule = new Ema50ProximityRule();
    }

    @Test
    void nearTransition_firesEmaProximitySignal() {
        // price = 100.10, ema50 = 100.00 → 0.10% < 0.15% threshold → NEAR
        BehaviourAlertContext ctx = context("100.10", "100.00", null, CLOSED_CANDLE);

        List<BehaviourAlertSignal> signals = rule.evaluate(ctx);

        assertEquals(1, signals.size());
        BehaviourAlertSignal s = signals.get(0);
        assertEquals(BehaviourAlertCategory.EMA_PROXIMITY, s.category());
        assertTrue(s.message().contains("EMA50"));
        assertEquals("MCL", s.instrument());
    }

    @Test
    void farState_noSignal() {
        // price = 102.00, ema50 = 100.00 → 2% > threshold → FAR
        BehaviourAlertContext ctx = context("102.00", "100.00", null, CLOSED_CANDLE);

        assertTrue(rule.evaluate(ctx).isEmpty());
    }

    @Test
    void noRefireOnSameCandle() {
        BehaviourAlertContext ctx = context("100.10", "100.00", null, CLOSED_CANDLE);

        rule.evaluate(ctx); // first evaluation — fires
        List<BehaviourAlertSignal> second = rule.evaluate(ctx); // same candle

        assertTrue(second.isEmpty(), "Must not re-fire on the same candle");
    }

    @Test
    void guardOpenCandle_fireClosed() {
        // null lastCandleTimestamp = open (unconfirmed) candle → must not fire
        BehaviourAlertContext openCtx   = context("100.10", "100.00", null, null);
        BehaviourAlertContext closedCtx = context("100.10", "100.00", null, CLOSED_CANDLE);

        assertTrue(rule.evaluate(openCtx).isEmpty(), "Must not fire on open candle");

        List<BehaviourAlertSignal> signals = rule.evaluate(closedCtx);
        assertEquals(1, signals.size(), "Must fire after candle closes");
    }

    @Test
    void farToNearToFar_refirePossibleOnNewNear() {
        BehaviourAlertContext near1 = context("100.10", "100.00", null, CLOSED_CANDLE);
        BehaviourAlertContext far   = context("102.00", "100.00", null, CLOSED_CANDLE);
        BehaviourAlertContext near2 = context("100.05", "100.00", null, NEXT_CANDLE);

        assertEquals(1, rule.evaluate(near1).size()); // fires
        assertTrue(rule.evaluate(far).isEmpty());     // FAR — resets state
        assertEquals(1, rule.evaluate(near2).size()); // NEAR again on new candle — fires
    }

    // ---- helpers ----

    private static BehaviourAlertContext context(String price, String ema50, String ema200, Instant candle) {
        return new BehaviourAlertContext(
                "MCL", "10m",
                price  != null ? new BigDecimal(price)  : null,
                ema50  != null ? new BigDecimal(ema50)  : null,
                ema200 != null ? new BigDecimal(ema200) : null,
                Collections.emptyList(),
                candle,
                null, null
        );
    }
}
