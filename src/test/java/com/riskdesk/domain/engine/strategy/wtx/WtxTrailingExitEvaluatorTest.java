package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WtxTrailingExitEvaluatorTest {

    private static final WtxConfig CONFIG = WtxConfig.defaults();

    @Test
    void flatPosition_returnsNoExit() {
        WtxStrategyState flat = WtxStrategyState.initial("MCL", "10m", BigDecimal.valueOf(10000));
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(
                flat, candle(100, 101, 99, 100), CONFIG);
        assertFalse(d.shouldExit());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.NONE, d.reason());
    }

    @Test
    void long_initialStopHit_beforeActivation() {
        // ATR=1.0, slMult=1.4 → initial stop = entry-1.4 = 98.6
        WtxStrategyState state = openLong(100.0, 1.0);
        // Candle dips to 98.5 — touches stop
        Candle c = candle(100, 100.2, 98.5, 99.0);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(state, c, CONFIG);
        assertTrue(d.shouldExit());
        assertEquals(WtxAction.CLOSE_LONG, d.exitAction());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.INITIAL_STOP, d.reason());
    }

    @Test
    void long_noExit_whenLowAboveStop() {
        WtxStrategyState state = openLong(100.0, 1.0);
        // Stays above 98.6 — no exit
        Candle c = candle(100, 100.5, 99.0, 100.2);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(state, c, CONFIG);
        assertFalse(d.shouldExit());
        // bestFavorablePrice should be updated to candle high
        assertEquals(0, BigDecimal.valueOf(100.5).compareTo(d.updatedBestFavorablePrice()));
    }

    @Test
    void long_trailingArmsAfterActivation() {
        // Activation distance = slMult*activationR*ATR = 1.4*0.5*1 = 0.7 → MFE must be >= 100.7
        WtxStrategyState state = openLong(100.0, 1.0);
        // Candle reaches 101.5 (well past activation), no exit
        Candle c = candle(100, 101.5, 100.3, 101.0);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(state, c, CONFIG);
        assertFalse(d.shouldExit());
        // Trailing stop = mfe - trailMult*ATR = 101.5 - 2.0 = 99.5
        assertNotNull(d.updatedTrailingStopPrice());
        assertEquals(0, BigDecimal.valueOf(99.5).compareTo(d.updatedTrailingStopPrice()));
    }

    @Test
    void short_initialStopHit_beforeActivation() {
        WtxStrategyState state = openShort(100.0, 1.0);
        // Initial stop = 100 + 1.4 = 101.4 — candle high pierces, low stays close to entry
        // (favorable move = 100 - 99.6 = 0.4 < 0.7 activation, so trailing stays disarmed)
        Candle c = candle(100, 101.5, 99.6, 100.5);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(state, c, CONFIG);
        assertTrue(d.shouldExit());
        assertEquals(WtxAction.CLOSE_SHORT, d.exitAction());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.INITIAL_STOP, d.reason());
    }

    @Test
    void nullEntryAtr_returnsNoExit() {
        WtxStrategyState state = WtxStrategyState.initial("MCL", "10m", BigDecimal.valueOf(10000))
                .withPosition(WtxPosition.LONG, BigDecimal.valueOf(100), BigDecimal.valueOf(1));
        // entryAtr is null because we used the 3-arg withPosition (legacy)
        // Strictly, this branch shouldn't trip because the service always passes ATR — but the guard exists
        Candle c = candle(100, 100.5, 99.0, 100);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(state, c, CONFIG);
        assertFalse(d.shouldExit());
    }

    @Test
    void currentStop_flat_returnsNull() {
        WtxStrategyState flat = WtxStrategyState.initial("MCL", "10m", BigDecimal.valueOf(10000));
        assertNull(WtxTrailingExitEvaluator.currentStop(flat, CONFIG));
    }

    @Test
    void currentStop_freshLong_derivesInitialAtrStop() {
        // No trailing eval has run yet → trailingStopPrice is null.
        // Initial stop = entry - slMult*ATR = 100 - 1.4*1 = 98.6 must still be exposed.
        WtxStrategyState state = openLong(100.0, 1.0);
        assertNull(state.trailingStopPrice());
        assertEquals(0, BigDecimal.valueOf(98.6).compareTo(
                WtxTrailingExitEvaluator.currentStop(state, CONFIG)));
    }

    @Test
    void currentStop_freshShort_derivesInitialAtrStop() {
        // Initial stop = entry + slMult*ATR = 100 + 1.4*1 = 101.4
        WtxStrategyState state = openShort(100.0, 1.0);
        assertEquals(0, BigDecimal.valueOf(101.4).compareTo(
                WtxTrailingExitEvaluator.currentStop(state, CONFIG)));
    }

    @Test
    void currentStop_armedTrailing_returnsRatchetedLevel() {
        WtxStrategyState state = openLong(100.0, 1.0)
                .withTrailing(BigDecimal.valueOf(101.5), BigDecimal.valueOf(99.5));
        assertEquals(0, BigDecimal.valueOf(99.5).compareTo(
                WtxTrailingExitEvaluator.currentStop(state, CONFIG)));
    }

    private static WtxStrategyState openLong(double entry, double atr) {
        return WtxStrategyState.initial("MCL", "10m", BigDecimal.valueOf(10000))
                .withPosition(WtxPosition.LONG, BigDecimal.valueOf(entry), BigDecimal.valueOf(1),
                        BigDecimal.valueOf(atr));
    }

    private static WtxStrategyState openShort(double entry, double atr) {
        return WtxStrategyState.initial("MCL", "10m", BigDecimal.valueOf(10000))
                .withPosition(WtxPosition.SHORT, BigDecimal.valueOf(entry), BigDecimal.valueOf(1),
                        BigDecimal.valueOf(atr));
    }

    private static Candle candle(double open, double high, double low, double close) {
        return new Candle(Instrument.MCL, "10m", Instant.now(),
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                100L);
    }
}
