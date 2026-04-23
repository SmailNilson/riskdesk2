package com.riskdesk.domain.trading.vo;

import com.riskdesk.domain.shared.vo.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class RiskRewardRatioTest {

    // ── Happy path: valid dollar-denominated inputs ───────────────────────

    @Test
    void calculate_validRiskAndReward_returnsRatio() {
        Money risk = Money.of("100.00");
        Money reward = Money.of("300.00");
        RiskRewardRatio ratio = RiskRewardRatio.calculate(risk, reward);
        assertNotNull(ratio);
        assertTrue(ratio.isDefined());
        assertNull(ratio.reason(), "reason is only populated for UNDEFINED");
        assertEquals(0, new BigDecimal("3.00").compareTo(ratio.value()));
    }

    @Test
    void calculate_equalRiskAndReward_returnsOne() {
        Money risk = Money.of("50.00");
        Money reward = Money.of("50.00");
        RiskRewardRatio ratio = RiskRewardRatio.calculate(risk, reward);
        assertNotNull(ratio);
        assertTrue(ratio.isDefined());
        assertEquals(0, new BigDecimal("1.00").compareTo(ratio.value()));
    }

    @Test
    void calculate_rewardLessThanRisk_returnsLessThanOne() {
        Money risk = Money.of("200.00");
        Money reward = Money.of("100.00");
        RiskRewardRatio ratio = RiskRewardRatio.calculate(risk, reward);
        assertNotNull(ratio);
        assertTrue(ratio.isDefined());
        assertEquals(0, new BigDecimal("0.50").compareTo(ratio.value()));
    }

    @Test
    void value_hasScaleTwo() {
        Money risk = Money.of("100.00");
        Money reward = Money.of("333.00");
        RiskRewardRatio ratio = RiskRewardRatio.calculate(risk, reward);
        assertNotNull(ratio);
        assertEquals(2, ratio.value().scale());
    }

    // ── PR-13 · No silent null on programmer bugs ─────────────────────────

    @Test
    void nullRisk_throws_insteadOfSilentNull() {
        NullPointerException npe = assertThrows(NullPointerException.class,
            () -> RiskRewardRatio.calculate(null, Money.of("100.00")));
        assertTrue(npe.getMessage() != null && npe.getMessage().contains("risk"),
            "NPE message must name the offending argument for fast debugging: " + npe.getMessage());
    }

    @Test
    void nullReward_throws_insteadOfSilentNull() {
        NullPointerException npe = assertThrows(NullPointerException.class,
            () -> RiskRewardRatio.calculate(Money.of("100.00"), null));
        assertTrue(npe.getMessage() != null && npe.getMessage().contains("reward"),
            "NPE message must name the offending argument for fast debugging: " + npe.getMessage());
    }

    // ── PR-13 · Sentinel (not null) on degenerate dollar inputs ───────────

    @Test
    void zeroRisk_returnsUndefinedSentinel_notNull() {
        RiskRewardRatio ratio = RiskRewardRatio.calculate(Money.ZERO, Money.of("100.00"));

        assertNotNull(ratio, "zero risk must NOT silently return null");
        assertFalse(ratio.isDefined());
        assertNull(ratio.value());
        assertNotNull(ratio.reason(), "UNDEFINED must explain why");
        assertTrue(ratio.reason().toLowerCase().contains("risk"),
            "reason should mention risk: " + ratio.reason());
    }

    @Test
    void zeroReward_returnsUndefinedSentinel_notNull() {
        // A trade with zero reward has no profit potential — well-defined mathematically
        // (ratio = 0) but meaningless tradably. Flagged as UNDEFINED so it cannot slip
        // through dashboards as "RR = 0, looks fine".
        RiskRewardRatio ratio = RiskRewardRatio.calculate(Money.of("100.00"), Money.ZERO);

        assertNotNull(ratio, "zero reward must NOT silently return null");
        assertFalse(ratio.isDefined());
        assertNull(ratio.value());
        assertNotNull(ratio.reason());
        assertTrue(ratio.reason().toLowerCase().contains("reward"),
            "reason should mention reward: " + ratio.reason());
    }

    @Test
    void negativeRisk_returnsUndefinedSentinel() {
        // Negative risk means the plan is wrong-signed (e.g. SL above entry for LONG).
        // Previously the VO would compute a nonsense negative ratio and let it through.
        Money negRisk = Money.of("100.00").negate();
        RiskRewardRatio ratio = RiskRewardRatio.calculate(negRisk, Money.of("200.00"));

        assertNotNull(ratio);
        assertFalse(ratio.isDefined());
        assertTrue(ratio.reason().toLowerCase().contains("risk"));
    }

    @Test
    void negativeReward_returnsUndefinedSentinel() {
        // Negative reward means TP is on the wrong side of entry — inverted plan.
        Money negReward = Money.of("50.00").negate();
        RiskRewardRatio ratio = RiskRewardRatio.calculate(Money.of("100.00"), negReward);

        assertNotNull(ratio);
        assertFalse(ratio.isDefined());
        assertTrue(ratio.reason().toLowerCase().contains("reward"));
    }

    @Test
    void undefinedFactory_requiresReason() {
        assertThrows(NullPointerException.class,
            () -> RiskRewardRatio.undefined(null));
    }

    @Test
    void undefinedFactory_preservesReason() {
        RiskRewardRatio sentinel = RiskRewardRatio.undefined("entry equals SL");
        assertFalse(sentinel.isDefined());
        assertEquals("entry equals SL", sentinel.reason());
        assertNull(sentinel.value());
    }

    @Test
    void toString_showsRatioWhenDefined() {
        RiskRewardRatio ratio = RiskRewardRatio.calculate(Money.of("100.00"), Money.of("250.00"));
        assertTrue(ratio.toString().contains("2.50"));
    }

    @Test
    void toString_showsReasonWhenUndefined() {
        RiskRewardRatio ratio = RiskRewardRatio.undefined("test reason");
        assertTrue(ratio.toString().contains("UNDEFINED"));
        assertTrue(ratio.toString().contains("test reason"));
    }
}
