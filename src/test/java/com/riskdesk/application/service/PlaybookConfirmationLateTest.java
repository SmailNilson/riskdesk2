package com.riskdesk.application.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirmation entries route a STOP at their own buy-stop / sell-stop. The late-entry
 * gate must therefore be measured against that confirmation entry — not the generic
 * retest entry — so a breakout order still resting below its trigger is NOT skipped.
 */
class PlaybookConfirmationLateTest {

    private static final BigDecimal ATR = new BigDecimal("20"); // 0.5×ATR tolerance = 10 pts

    @Test
    void longPendingBelowBuyStopIsNotLate() {
        // Buy-stop 30828.25, market 30825.00 (below the trigger) → genuinely pending, route it.
        assertFalse(PlaybookAutomationService.confirmationLate(
            "LONG", new BigDecimal("30828.25"), new BigDecimal("30825.00"), ATR));
    }

    @Test
    void longWithinToleranceAboveBuyStopIsNotLate() {
        // 8 pts above the trigger, under the 10-pt (0.5×ATR) tolerance → still fillable.
        assertFalse(PlaybookAutomationService.confirmationLate(
            "LONG", new BigDecimal("30828.25"), new BigDecimal("30836.25"), ATR));
    }

    @Test
    void longBlownThroughBuyStopIsLate() {
        // 15 pts above the trigger (> 10-pt tolerance) → price already ran, chasing.
        assertTrue(PlaybookAutomationService.confirmationLate(
            "LONG", new BigDecimal("30828.25"), new BigDecimal("30843.25"), ATR));
    }

    @Test
    void shortPendingAboveSellStopIsNotLate() {
        // Sell-stop 30500, market 30505 (above the trigger) → pending, route it.
        assertFalse(PlaybookAutomationService.confirmationLate(
            "SHORT", new BigDecimal("30500.00"), new BigDecimal("30505.00"), ATR));
    }

    @Test
    void shortBlownThroughSellStopIsLate() {
        // 15 pts below the trigger (> tolerance) → chasing the breakdown.
        assertTrue(PlaybookAutomationService.confirmationLate(
            "SHORT", new BigDecimal("30500.00"), new BigDecimal("30485.00"), ATR));
    }

    @Test
    void missingInputsFailOpenAsNotLate() {
        assertFalse(PlaybookAutomationService.confirmationLate("LONG", null, new BigDecimal("30825"), ATR));
        assertFalse(PlaybookAutomationService.confirmationLate("LONG", new BigDecimal("30828"), null, ATR));
        assertFalse(PlaybookAutomationService.confirmationLate("LONG", new BigDecimal("30828"), new BigDecimal("30825"), null));
        assertFalse(PlaybookAutomationService.confirmationLate("LONG", new BigDecimal("30828"), new BigDecimal("30825"), BigDecimal.ZERO));
    }
}
