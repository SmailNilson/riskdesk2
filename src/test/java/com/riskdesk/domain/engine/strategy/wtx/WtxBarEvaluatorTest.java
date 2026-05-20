package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.engine.indicators.WaveTrendIndicator.WaveTrendResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WtxBarEvaluatorTest {

    private static final WtxConfig CONFIG = WtxConfig.defaults();
    private static final Instant NOW = Instant.parse("2026-05-13T14:00:00Z");

    @Test
    void crossOverInOversold_emitsCompraOpenLong() {
        // prev: wt1 below wt2 ; curr: wt1 above wt2 AND below nsv (-53)
        WaveTrendResult prev = wt(-60, -55);
        WaveTrendResult curr = wt(-54, -56);
        WtxStrategyState state = state(WtxProfile.BASELINE, WtxPosition.FLAT);

        Optional<WtxSignal> result = WtxBarEvaluator.evaluate(prev, curr, CONFIG, state, NOW, "10m");

        assertTrue(result.isPresent());
        assertEquals(WtxSignalType.COMPRA, result.get().signalType());
        assertEquals(WtxAction.OPEN_LONG, result.get().suggestedAction());
        assertTrue(result.get().canTrade());
    }

    @Test
    void htfBearishBlocksLong_evenOnCrossover() {
        WaveTrendResult prev = wt(-60, -55);
        WaveTrendResult curr = wt(-54, -56);
        WtxStrategyState state = state(WtxProfile.HTF, WtxPosition.FLAT);
        WtxHtfBiasFilter.Decision htfBearish = new WtxHtfBiasFilter.Decision(false, WtxHtfBiasFilter.HtfBias.BEARISH);

        Optional<WtxSignal> result = WtxBarEvaluator.evaluate(
                prev, curr, CONFIG, state, NOW, "10m", htfBearish, null);

        assertTrue(result.isPresent());
        assertEquals(WtxAction.NONE, result.get().suggestedAction(),
                "HTF bearish must force action to NONE even on a valid WT crossover");
    }

    @Test
    void structureBlocksLong_underStrict() {
        WaveTrendResult prev = wt(-60, -55);
        WaveTrendResult curr = wt(-54, -56);
        WtxStrategyState state = state(WtxProfile.STRICT, WtxPosition.FLAT);
        WtxHtfBiasFilter.Decision htfOk = new WtxHtfBiasFilter.Decision(true, WtxHtfBiasFilter.HtfBias.NEUTRAL);
        WtxStructureFilter.Decision blocked = new WtxStructureFilter.Decision(false, WtxStructureFilter.StructureReason.BLOCKED);

        Optional<WtxSignal> result = WtxBarEvaluator.evaluate(
                prev, curr, CONFIG, state, NOW, "10m", htfOk, blocked);

        assertTrue(result.isPresent());
        assertEquals(WtxAction.NONE, result.get().suggestedAction());
    }

    @Test
    void sessionAtr_maxLossHit_forcesActionNone() {
        WaveTrendResult prev = wt(-60, -55);
        WaveTrendResult curr = wt(-54, -56);
        WtxStrategyState state = state(WtxProfile.SESSION_ATR, WtxPosition.FLAT).withMaxLossHit();

        Optional<WtxSignal> result = WtxBarEvaluator.evaluate(prev, curr, CONFIG, state, NOW, "10m");

        assertTrue(result.isPresent());
        assertEquals(WtxAction.NONE, result.get().suggestedAction());
    }

    @Test
    void baseline_maxLossHit_doesNotBlock() {
        WaveTrendResult prev = wt(-60, -55);
        WaveTrendResult curr = wt(-54, -56);
        WtxStrategyState state = state(WtxProfile.BASELINE, WtxPosition.FLAT).withMaxLossHit();

        Optional<WtxSignal> result = WtxBarEvaluator.evaluate(prev, curr, CONFIG, state, NOW, "10m");

        assertTrue(result.isPresent());
        assertEquals(WtxAction.OPEN_LONG, result.get().suggestedAction(),
                "BASELINE profile preserves legacy behaviour and ignores max-loss flag");
    }

    @Test
    void shortPosition_reverseOnOpp_emitsReverseToLong() {
        WaveTrendResult prev = wt(-60, -55);
        WaveTrendResult curr = wt(-54, -56);
        WtxStrategyState state = state(WtxProfile.BASELINE, WtxPosition.SHORT);

        Optional<WtxSignal> result = WtxBarEvaluator.evaluate(prev, curr, CONFIG, state, NOW, "10m");

        assertTrue(result.isPresent());
        assertEquals(WtxAction.REVERSE_TO_LONG, result.get().suggestedAction());
    }

    private static WaveTrendResult wt(double wt1, double wt2) {
        BigDecimal a = BigDecimal.valueOf(wt1);
        BigDecimal b = BigDecimal.valueOf(wt2);
        return new WaveTrendResult(a, b, a.subtract(b), null, null);
    }

    private static WtxStrategyState state(WtxProfile profile, WtxPosition pos) {
        WtxStrategyState s = WtxStrategyState.initial("MCL", "10m", BigDecimal.valueOf(10000)).withProfile(profile);
        return pos == WtxPosition.FLAT
                ? s
                : s.withPosition(pos, BigDecimal.valueOf(100), BigDecimal.valueOf(1), BigDecimal.valueOf(1));
    }
}
