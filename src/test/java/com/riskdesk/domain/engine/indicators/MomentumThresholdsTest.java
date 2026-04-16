package com.riskdesk.domain.engine.indicators;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-6 · Verifies the RSI interpretation thresholds used by
 * {@code AgentContext.MomentumSnapshot} are consistent and form a
 * coherent band. Refactoring these into a central helper eliminates
 * scattered magic numbers across divergence / confirmation logic.
 */
class MomentumThresholdsTest {

    @Test
    void rsiOversoldAndOverboughtMatchWilderStandards() {
        assertThat(MomentumThresholds.RSI_OVERSOLD).isEqualTo(30.0);
        assertThat(MomentumThresholds.RSI_OVERBOUGHT).isEqualTo(70.0);
    }

    @Test
    void rsiDirectionalConfirmationBandsAreAligned() {
        // LONG confirmation: RSI strictly inside (40, 70)
        assertThat(MomentumThresholds.RSI_CONFIRM_LONG_MIN).isEqualTo(40.0);
        assertThat(MomentumThresholds.RSI_CONFIRM_LONG_MAX).isEqualTo(MomentumThresholds.RSI_OVERBOUGHT);

        // SHORT confirmation: RSI strictly inside (30, 60)
        assertThat(MomentumThresholds.RSI_CONFIRM_SHORT_MIN).isEqualTo(MomentumThresholds.RSI_OVERSOLD);
        assertThat(MomentumThresholds.RSI_CONFIRM_SHORT_MAX).isEqualTo(60.0);
    }

    @Test
    void rsiDivergenceCutOffsSitInsideConfirmationBands() {
        // Divergence detection is a looser gate than confirmation:
        // bearish divergence fires while RSI is still above oversold but weakening below 55.
        assertThat(MomentumThresholds.RSI_BEARISH_DIVERGENCE_MAX).isEqualTo(55.0);
        assertThat(MomentumThresholds.RSI_BULLISH_DIVERGENCE_MIN).isEqualTo(45.0);
    }

    @Test
    void thresholdsFormCoherentAscendingBand() {
        // OS < CONFIRM_LONG_MIN < BULL_DIVERGENCE_MIN < BEAR_DIVERGENCE_MAX < CONFIRM_SHORT_MAX < OB
        assertThat(MomentumThresholds.RSI_OVERSOLD)
            .isLessThan(MomentumThresholds.RSI_CONFIRM_LONG_MIN);
        assertThat(MomentumThresholds.RSI_CONFIRM_LONG_MIN)
            .isLessThan(MomentumThresholds.RSI_BULLISH_DIVERGENCE_MIN);
        assertThat(MomentumThresholds.RSI_BULLISH_DIVERGENCE_MIN)
            .isLessThan(MomentumThresholds.RSI_BEARISH_DIVERGENCE_MAX);
        assertThat(MomentumThresholds.RSI_BEARISH_DIVERGENCE_MAX)
            .isLessThan(MomentumThresholds.RSI_CONFIRM_SHORT_MAX);
        assertThat(MomentumThresholds.RSI_CONFIRM_SHORT_MAX)
            .isLessThan(MomentumThresholds.RSI_OVERBOUGHT);
    }

    @Test
    void longAndShortConfirmationBandsHaveEqualWidth() {
        // Both confirmation bands are 30-point wide but directionally shifted:
        // LONG expects elevated RSI (40..70), SHORT expects depressed RSI (30..60).
        // Equal width ensures neither direction has a structural advantage.
        double longRange = MomentumThresholds.RSI_CONFIRM_LONG_MAX - MomentumThresholds.RSI_CONFIRM_LONG_MIN;
        double shortRange = MomentumThresholds.RSI_CONFIRM_SHORT_MAX - MomentumThresholds.RSI_CONFIRM_SHORT_MIN;
        assertThat(longRange).isEqualTo(30.0);
        assertThat(shortRange).isEqualTo(30.0);
    }

    @Test
    void confirmationBandMidpointsAreMirroredAroundFifty() {
        // LONG midpoint (55) and SHORT midpoint (45) should be equidistant
        // from the 50 neutral line — ensures directional symmetry of bias.
        double longMidpoint = (MomentumThresholds.RSI_CONFIRM_LONG_MAX + MomentumThresholds.RSI_CONFIRM_LONG_MIN) / 2;
        double shortMidpoint = (MomentumThresholds.RSI_CONFIRM_SHORT_MAX + MomentumThresholds.RSI_CONFIRM_SHORT_MIN) / 2;
        assertThat(longMidpoint - 50.0).isEqualTo(50.0 - shortMidpoint);
    }

    @Test
    void divergenceCutOffsAreSymmetricAroundFifty() {
        // 55 and 45 are equidistant from the 50 midpoint — reinforces that
        // divergence is a symmetric deviation signal, not a directional bias.
        double bearishDistance = MomentumThresholds.RSI_BEARISH_DIVERGENCE_MAX - 50.0;
        double bullishDistance = 50.0 - MomentumThresholds.RSI_BULLISH_DIVERGENCE_MIN;
        assertThat(bearishDistance).isEqualTo(bullishDistance);
    }
}
