package com.riskdesk.domain.engine.strategy.wtx;

import static org.assertj.core.api.Assertions.assertThat;

import com.riskdesk.domain.engine.strategy.wtx.WtxHtfBiasFilter.HtfBias;
import org.junit.jupiter.api.Test;

/**
 * The "A2" HTF-bias early-exit rule: a held position is closed only when the 1h bias has stopped
 * supporting its direction (turned NEUTRAL or opposite). A supporting or UNAVAILABLE bias never
 * forces an exit, and a FLAT book is always a no-op.
 */
class WtxHtfBiasExitEvaluatorTest {

    // ── LONG ────────────────────────────────────────────────────────────────
    @Test
    void longExitsWhenBiasTurnsBearish() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.LONG, HtfBias.BEARISH)).isTrue();
    }

    @Test
    void longExitsWhenBiasTurnsNeutral() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.LONG, HtfBias.NEUTRAL)).isTrue();
    }

    @Test
    void longKeepsWhenBiasStillBullish() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.LONG, HtfBias.BULLISH)).isFalse();
    }

    @Test
    void longKeepsWhenBiasUnavailable() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.LONG, HtfBias.UNAVAILABLE)).isFalse();
    }

    // ── SHORT ───────────────────────────────────────────────────────────────
    @Test
    void shortExitsWhenBiasTurnsBullish() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.SHORT, HtfBias.BULLISH)).isTrue();
    }

    @Test
    void shortExitsWhenBiasTurnsNeutral() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.SHORT, HtfBias.NEUTRAL)).isTrue();
    }

    @Test
    void shortKeepsWhenBiasStillBearish() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.SHORT, HtfBias.BEARISH)).isFalse();
    }

    @Test
    void shortKeepsWhenBiasUnavailable() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.SHORT, HtfBias.UNAVAILABLE)).isFalse();
    }

    // ── FLAT / null guards ────────────────────────────────────────────────────
    @Test
    void flatIsAlwaysNoOp() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.FLAT, HtfBias.BEARISH)).isFalse();
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.FLAT, HtfBias.NEUTRAL)).isFalse();
    }

    @Test
    void nullArgsAreNoOp() {
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(null, HtfBias.BEARISH)).isFalse();
        assertThat(WtxHtfBiasExitEvaluator.shouldExit(WtxPosition.LONG, null)).isFalse();
    }
}
