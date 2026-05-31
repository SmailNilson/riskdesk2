package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the {@link WtxRsiTransition} reducer — one per transition.
 * No Spring, no ports, no broker: just (state, bar, signal) → decisions.
 */
class WtxRsiTransitionTest {

    private final WtxRsiConfig config = WtxRsiConfig.defaults5m(); // REVERSAL, chaikinEnabled
    private static final Instant TS = Instant.parse("2025-01-02T15:00:00Z");

    // ── protective exit ──────────────────────────────────────────────────────

    @Test
    void protective_exit_pessimistic_sl_wins_when_both_touched() {
        WtxRsiStrategyState longPos = flat(false).withPosition(
                WtxRsiPosition.LONG, bd(17000), BigDecimal.ONE, bd(16990), bd(17010));
        // Bar straddles both SL (low) and TP (high): SL must win.
        Candle bar = candle(17000, 17012, 16985, 17005);

        WtxRsiTransition.Result r = WtxRsiTransition.reduce(
                longPos, bar, List.of(bar), Optional.empty(), WtxRsiSwingBias.NEUTRAL, config);

        assertEquals(1, r.decisions().size());
        WtxRsiDecision.Close close = assertClose(r.decisions().get(0));
        assertEquals(WtxRsiDecision.CloseCause.STOP_LOSS, close.cause());
        assertEquals(0, close.exitPrice().compareTo(bd(16990)));
        assertEquals(WtxRsiPosition.FLAT, r.newState().currentPosition());
    }

    @Test
    void protective_exit_take_profit_when_only_tp_touched() {
        WtxRsiStrategyState longPos = flat(false).withPosition(
                WtxRsiPosition.LONG, bd(17000), BigDecimal.ONE, bd(16990), bd(17010));
        Candle bar = candle(17000, 17015, 16998, 17012); // TP only

        WtxRsiTransition.Result r = WtxRsiTransition.reduce(
                longPos, bar, List.of(bar), Optional.empty(), WtxRsiSwingBias.NEUTRAL, config);

        WtxRsiDecision.Close close = assertClose(r.decisions().get(0));
        assertEquals(WtxRsiDecision.CloseCause.TAKE_PROFIT, close.cause());
        assertEquals(0, close.exitPrice().compareTo(bd(17010)));
    }

    // ── same-side suppress ─────────────────────────────────────────────────────

    @Test
    void same_side_signal_while_open_is_suppressed() {
        WtxRsiStrategyState longPos = flat(false).withPosition(
                WtxRsiPosition.LONG, bd(17000), BigDecimal.ONE, bd(16990), null);
        Candle bar = candle(17000, 17005, 16995, 17001); // no SL/TP touch
        WtxRsiSignal sameSide = signal(0, WtxRsiSignal.Side.LONG, true, 17001);

        WtxRsiTransition.Result r = WtxRsiTransition.reduce(
                longPos, bar, List.of(bar), Optional.of(sameSide), WtxRsiSwingBias.NEUTRAL, config);

        assertEquals(1, r.decisions().size());
        assertInstanceOf(WtxRsiDecision.Suppress.class, r.decisions().get(0));
        assertNull(((WtxRsiDecision.Suppress) r.decisions().get(0)).reason());
        assertEquals(WtxRsiPosition.LONG, r.newState().currentPosition(), "position unchanged");
    }

    // ── reverse-on-opposite ─────────────────────────────────────────────────────

    @Test
    void opposite_signal_closes_then_attempts_reverse() {
        WtxRsiStrategyState longPos = flat(false).withPosition(
                WtxRsiPosition.LONG, bd(17000), BigDecimal.ONE, bd(16990), null);
        Candle bar = candle(17000, 17005, 16995, 17002);
        WtxRsiSignal opposite = signal(0, WtxRsiSignal.Side.SHORT, false, 17002);

        WtxRsiTransition.Result r = WtxRsiTransition.reduce(
                longPos, bar, List.of(bar), Optional.of(opposite), WtxRsiSwingBias.NEUTRAL, config);

        // First decision must be the REVERSAL close at the signal close price.
        WtxRsiDecision.Close close = assertClose(r.decisions().get(0));
        assertEquals(WtxRsiDecision.CloseCause.REVERSAL, close.cause());
        assertEquals(0, close.exitPrice().compareTo(bd(17002)));
        assertNotNull(close.signal(), "reverse close carries the triggering signal");
        // With a single-candle list no fractal exists → the reverse entry is rejected.
        assertInstanceOf(WtxRsiDecision.Reject.class, r.decisions().get(1));
        assertEquals(WtxRsiPosition.FLAT, r.newState().currentPosition());
    }

    // ── chaikin gate ───────────────────────────────────────────────────────────

    @Test
    void chaikin_required_blocks_unconfirmed_entry() {
        WtxRsiStrategyState flat = flat(true); // chaikinRequired = true
        Candle bar = candle(17000, 17005, 16995, 17001);
        WtxRsiSignal unconfirmed = signal(0, WtxRsiSignal.Side.LONG, false, 17001);

        WtxRsiTransition.Result r = WtxRsiTransition.reduce(
                flat, bar, List.of(bar), Optional.of(unconfirmed), WtxRsiSwingBias.NEUTRAL, config);

        assertEquals(1, r.decisions().size());
        assertInstanceOf(WtxRsiDecision.Block.class, r.decisions().get(0));
        assertEquals(WtxRsiPosition.FLAT, r.newState().currentPosition());
    }

    // ── swing-bias filter ───────────────────────────────────────────────────────

    @Test
    void swing_bias_filter_suppresses_contradictory_fresh_signal() {
        WtxRsiStrategyState flat = flat(false).withSwingBiasFilter(true);
        Candle bar = candle(17000, 17005, 16995, 17001);
        WtxRsiSignal longSig = signal(0, WtxRsiSignal.Side.LONG, true, 17001);

        WtxRsiTransition.Result r = WtxRsiTransition.reduce(
                flat, bar, List.of(bar), Optional.of(longSig), WtxRsiSwingBias.BEARISH, config);

        assertEquals(1, r.decisions().size());
        WtxRsiDecision.Suppress s = assertInstanceOf(WtxRsiDecision.Suppress.class, r.decisions().get(0));
        assertEquals("swing-bias filter: BEARISH", s.reason());
    }

    @Test
    void swing_bias_filter_force_closes_open_position_against_bias() {
        WtxRsiStrategyState longPos = flat(false).withSwingBiasFilter(true).withPosition(
                WtxRsiPosition.LONG, bd(17000), BigDecimal.ONE, bd(16980), null);
        Candle bar = candle(17000, 17005, 16995, 17001); // no SL touch
        // No fresh signal — pure bias-flip force close.
        WtxRsiTransition.Result r = WtxRsiTransition.reduce(
                longPos, bar, List.of(bar), Optional.empty(), WtxRsiSwingBias.BEARISH, config);

        WtxRsiDecision.Close close = assertClose(r.decisions().get(0));
        assertEquals(WtxRsiDecision.CloseCause.BIAS_FLIP, close.cause());
        assertEquals("swing-bias flip → BEARISH", close.reasonOverride());
        assertEquals(WtxRsiPosition.FLAT, r.newState().currentPosition());
    }

    // ── open (uses the shared synthetic fixture for a real fractal) ──────────────

    @Test
    void valid_signal_from_flat_opens_a_position() {
        List<Candle> bars = SyntheticCandles.mnq(500, 42);
        List<WtxRsiSignal> signals = WtxRsiBarEvaluator.detectAll(bars, config);

        WtxRsiSignal opening = null;
        for (WtxRsiSignal s : signals) {
            if (WtxRsiRiskCalculator.build(s, bars, bars.get(s.barIndex()).getClose(), config).isPresent()) {
                opening = s;
                break;
            }
        }
        assertNotNull(opening, "fixture must yield at least one buildable signal");

        WtxRsiStrategyState flat = flat(false); // chaikinRequired off so confirmation doesn't gate
        Candle bar = bars.get(opening.barIndex());
        WtxRsiTransition.Result r = WtxRsiTransition.reduce(
                flat, bar, bars, Optional.of(opening), WtxRsiSwingBias.NEUTRAL, config);

        WtxRsiDecision.Open open = assertInstanceOf(WtxRsiDecision.Open.class, r.decisions().get(0));
        WtxRsiPosition expected = opening.side() == WtxRsiSignal.Side.LONG
                ? WtxRsiPosition.LONG : WtxRsiPosition.SHORT;
        assertEquals(expected, r.newState().currentPosition());
        assertEquals(0, r.newState().entryPrice().compareTo(open.plan().entryPrice()));
        assertEquals(0, r.newState().stopLoss().compareTo(open.plan().stopLoss()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static WtxRsiStrategyState flat(boolean chaikinRequired) {
        return WtxRsiStrategyState.initial("MNQ", "5m", chaikinRequired);
    }

    private static WtxRsiDecision.Close assertClose(WtxRsiDecision d) {
        return assertInstanceOf(WtxRsiDecision.Close.class, d);
    }

    private static WtxRsiSignal signal(int barIndex, WtxRsiSignal.Side side, boolean confirmed, double close) {
        return new WtxRsiSignal(barIndex, TS, side, confirmed,
                bd(50), bd(45), bd(55), bd(50), bd(1), bd(close));
    }

    private static Candle candle(double open, double high, double low, double close) {
        return new Candle(Instrument.MNQ, "5m", TS,
                bd(open), bd(high), bd(low), bd(close), 1000L);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
