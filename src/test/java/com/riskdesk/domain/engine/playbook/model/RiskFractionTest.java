package com.riskdesk.domain.engine.playbook.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Locks the risk-sizing contract: a risk fraction is a dimensionless number
 * in {@code [0, MAX_FRACTION]} where {@code 0.01} means "1% of equity at risk",
 * not "0.01% of equity at risk".
 */
class RiskFractionTest {

    @Test
    void constantsMatchExpectedUnits() {
        assertEquals(0.10, RiskFraction.MAX_FRACTION);
        assertEquals(0.01, RiskFraction.FULL);    // 1% of equity
        assertEquals(0.005, RiskFraction.HALF);   // 0.5% of equity
        assertEquals(0.0, RiskFraction.ZERO);
    }

    @Test
    void toPercentConvertsFractionToDisplayUnit() {
        assertEquals(1.0, RiskFraction.toPercent(0.01));
        assertEquals(0.5, RiskFraction.toPercent(0.005));
        assertEquals(0.0, RiskFraction.toPercent(0.0));
    }

    @Test
    void fromPercentReversesToPercent() {
        assertEquals(0.01, RiskFraction.fromPercent(1.0));
        assertEquals(0.005, RiskFraction.fromPercent(0.5));
    }

    @Test
    void requireValidAcceptsZero() {
        assertEquals(0.0, RiskFraction.requireValid(0.0));
    }

    @Test
    void requireValidAcceptsBoundary() {
        assertEquals(RiskFraction.MAX_FRACTION,
            RiskFraction.requireValid(RiskFraction.MAX_FRACTION));
    }

    @Test
    void requireValidRejectsNegative() {
        assertThrows(IllegalArgumentException.class,
            () -> RiskFraction.requireValid(-0.001));
    }

    @Test
    void requireValidRejectsAboveMax() {
        // 1.0 would be a classic percent-vs-fraction confusion (meant "1%", got 100%).
        assertThrows(IllegalArgumentException.class,
            () -> RiskFraction.requireValid(1.0));
    }

    @Test
    void requireValidRejectsNaN() {
        assertThrows(IllegalArgumentException.class,
            () -> RiskFraction.requireValid(Double.NaN));
    }

    @Test
    void clampForcesIntoRange() {
        assertEquals(0.0, RiskFraction.clamp(-0.5));
        assertEquals(0.0, RiskFraction.clamp(Double.NaN));
        assertEquals(RiskFraction.MAX_FRACTION, RiskFraction.clamp(99.0));
        assertEquals(0.005, RiskFraction.clamp(0.005));
    }

    // ── Integration with domain records ──────────────────────────────────

    @Test
    void playbookPlanRejectsPercentUnitsPassedAsFraction() {
        // Classic bug: caller passes 1.0 thinking "1%", actually 100% of equity.
        assertThrows(IllegalArgumentException.class, () -> new PlaybookPlan(
            new BigDecimal("92.00"), new BigDecimal("90.00"),
            new BigDecimal("97.00"), new BigDecimal("99.00"),
            2.5, 1.0, "below zone", "next OB"));
    }

    @Test
    void playbookPlanAcceptsFractionInRange() {
        PlaybookPlan plan = new PlaybookPlan(
            new BigDecimal("92.00"), new BigDecimal("90.00"),
            new BigDecimal("97.00"), new BigDecimal("99.00"),
            2.5, 0.01, "below zone", "next OB");
        assertEquals(1.0, plan.riskPercentDisplay());
    }

    @Test
    void playbookPlanWithAdjustedSizeValidatesNewFraction() {
        PlaybookPlan plan = new PlaybookPlan(
            new BigDecimal("92.00"), new BigDecimal("90.00"),
            new BigDecimal("97.00"), new BigDecimal("99.00"),
            2.5, 0.01, "below zone", "next OB");
        assertThrows(IllegalArgumentException.class, () -> plan.withAdjustedSize(0.5));
    }
}
