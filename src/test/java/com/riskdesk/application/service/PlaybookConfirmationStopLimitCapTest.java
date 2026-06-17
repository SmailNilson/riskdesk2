package com.riskdesk.application.service;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the STOP-LIMIT slippage cap for confirmation entries. A plain STOP becomes a market order
 * and slips uncapped on a fast break (observed: trigger 30408.50, filled 30370.75 = 37.75 pts).
 * The cap sits {@code band × ATR} beyond the trigger, with ATR recovered from the stop distance
 * ({@code |trigger − SL| = 1.5 × ATR}).
 */
class PlaybookConfirmationStopLimitCapTest {

    // stopDist = 40 pts ⇒ ATR = 40 / 1.5 = 26.667 ⇒ band@0.3 = 8.0 pts.
    private static final BigDecimal TRIGGER = new BigDecimal("30408.50");
    private static final BigDecimal SHORT_SL = new BigDecimal("30448.50"); // SL above trigger
    private static final BigDecimal LONG_SL = new BigDecimal("30368.50");  // SL below trigger

    @Test
    void shortCapSitsBelowTriggerByBandAtr() {
        BigDecimal cap = PlaybookAutomationService.confirmationStopLimitCap(
            TRIGGER, SHORT_SL, true, 0.30, Instrument.MNQ);
        // 30408.50 − 8.0 = 30400.50
        assertThat(cap).isEqualByComparingTo("30400.50");
        assertThat(cap).isLessThan(TRIGGER);
    }

    @Test
    void longCapSitsAboveTriggerByBandAtr() {
        BigDecimal cap = PlaybookAutomationService.confirmationStopLimitCap(
            TRIGGER, LONG_SL, false, 0.30, Instrument.MNQ);
        // 30408.50 + 8.0 = 30416.50
        assertThat(cap).isEqualByComparingTo("30416.50");
        assertThat(cap).isGreaterThan(TRIGGER);
    }

    @Test
    void widerBandGivesAWiderCap() {
        BigDecimal tight = PlaybookAutomationService.confirmationStopLimitCap(
            TRIGGER, SHORT_SL, true, 0.15, Instrument.MNQ);
        BigDecimal wide = PlaybookAutomationService.confirmationStopLimitCap(
            TRIGGER, SHORT_SL, true, 0.45, Instrument.MNQ);
        // Wider band ⇒ cap further below the trigger ⇒ allows more slippage.
        assertThat(wide).isLessThan(tight);
    }

    @Test
    void disabledBandReturnsNull_fallsBackToPlainStop() {
        assertThat(PlaybookAutomationService.confirmationStopLimitCap(
            TRIGGER, SHORT_SL, true, 0.0, Instrument.MNQ)).isNull();
        assertThat(PlaybookAutomationService.confirmationStopLimitCap(
            TRIGGER, SHORT_SL, true, -1.0, Instrument.MNQ)).isNull();
    }

    @Test
    void nullStopOrZeroDistanceReturnsNull() {
        assertThat(PlaybookAutomationService.confirmationStopLimitCap(
            TRIGGER, null, true, 0.30, Instrument.MNQ)).isNull();
        assertThat(PlaybookAutomationService.confirmationStopLimitCap(
            TRIGGER, TRIGGER, true, 0.30, Instrument.MNQ)).isNull();
    }

    @Test
    void bandSmallerThanATickRoundsOntoTriggerAndReturnsNull() {
        // band ≈ 0.027 pt < half a tick (0.125) ⇒ rounds back to the trigger ⇒ no protection.
        assertThat(PlaybookAutomationService.confirmationStopLimitCap(
            TRIGGER, SHORT_SL, true, 0.001, Instrument.MNQ)).isNull();
    }
}
