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
    // POINTS mode: arm +30 / trail 15; slPoints=0 → dynamic slAtrMult*ATR stop.
    private static final WtxConfig POINTS = WtxConfig.defaults().withTrailing(
            WtxTrailingMode.POINTS, BigDecimal.valueOf(30), BigDecimal.valueOf(15), BigDecimal.ZERO);
    // POINTS mode with a fixed 30-point stop (slPoints overrides ATR).
    private static final WtxConfig POINTS_FIXED_SL = WtxConfig.defaults().withTrailing(
            WtxTrailingMode.POINTS, BigDecimal.valueOf(30), BigDecimal.valueOf(15), BigDecimal.valueOf(30));
    // SL_ONLY mode: no trailing ratchet; the fixed initial SL (slPoints=0 → slAtrMult*ATR) is the only stop.
    private static final WtxConfig SL_ONLY = WtxConfig.defaults().withTrailing(
            WtxTrailingMode.SL_ONLY, BigDecimal.valueOf(30), BigDecimal.valueOf(15), BigDecimal.ZERO);

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

    // ── POINTS mode ──────────────────────────────────────────────────────────

    @Test
    void points_long_initialStop_usesDynamicAtrWhenSlPointsZero() {
        // POINTS mode, slPoints=0 → dynamic SL = slAtrMult(defaults 1.4)*ATR = 1.4*10 = 14 → stop 29986
        WtxStrategyState s = openLong(30000, 10);
        Candle c = candle(30000, 30005, 29985, 29990); // low pierces 29986, favorable < 30 (disarmed)
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, POINTS);
        assertTrue(d.shouldExit());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.INITIAL_STOP, d.reason());
        assertEquals(0, BigDecimal.valueOf(29986).compareTo(d.exitPrice()));
    }

    @Test
    void points_long_trailArmsAt30pts_trails15pts() {
        // high 30040 >= entry+30 arms; trail = 30040 - 15 = 30025; low 30028 stays above → no exit
        WtxStrategyState s = openLong(30000, 10);
        Candle c = candle(30000, 30040, 30028, 30035);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, POINTS);
        assertFalse(d.shouldExit());
        assertEquals(0, BigDecimal.valueOf(30025).compareTo(d.updatedTrailingStopPrice()));
    }

    @Test
    void points_long_trailingStopHit_isTakeProfit() {
        // Armed at MFE 30040 (trail 30025); a later bar retraces to 30020 → trailing take-profit exit.
        WtxStrategyState s = openLong(30000, 10)
                .withTrailing(BigDecimal.valueOf(30040), BigDecimal.valueOf(30025));
        Candle c = candle(30030, 30032, 30020, 30022);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, POINTS);
        assertTrue(d.shouldExit());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.TRAILING_STOP, d.reason());
        // TRAILING_STOP maps to a take-profit exit type.
        assertEquals(WtxExitType.TRAILING_TP,
                WtxExitType.fromExitReason(d.reason()));
    }

    @Test
    void points_fixedSlPoints_overridesAtr() {
        // slPoints=30 fixed → stop 29970 regardless of the (small) ATR.
        WtxStrategyState s = openLong(30000, 5);
        Candle c = candle(30000, 30005, 29969, 29975);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, POINTS_FIXED_SL);
        assertTrue(d.shouldExit());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.INITIAL_STOP, d.reason());
        assertEquals(0, BigDecimal.valueOf(29970).compareTo(d.exitPrice()));
    }

    @Test
    void points_fixedSlPoints_worksWithoutAtr() {
        // POINTS + fixed slPoints needs no ATR — the stop is still computed and enforced.
        WtxStrategyState s = WtxStrategyState.initial("MCL", "10m", BigDecimal.valueOf(10000))
                .withPosition(WtxPosition.LONG, BigDecimal.valueOf(30000), BigDecimal.valueOf(1)); // null ATR
        assertEquals(0, BigDecimal.valueOf(29970).compareTo(
                WtxTrailingExitEvaluator.currentStop(s, POINTS_FIXED_SL)));
        Candle c = candle(30000, 30005, 29969, 29975);
        assertTrue(WtxTrailingExitEvaluator.evaluate(s, c, POINTS_FIXED_SL).shouldExit());
    }

    @Test
    void points_scopedToMnqOnly_otherInstrumentsUseAtrTrail() {
        // POINTS config scoped to MNQ only (production default). An MCL position must fall back to ATR:
        // trail = trailingAtrMult*ATR = 2.0*1 = 2.0 (not the 15-point trail), so MFE 101.5 → trail 99.5.
        WtxConfig pointsMnqOnly = WtxConfig.defaults()
                .withTrailing(WtxTrailingMode.POINTS, BigDecimal.valueOf(30), BigDecimal.valueOf(15), BigDecimal.ZERO)
                .withPointTrailingInstruments(java.util.List.of("MNQ"));
        WtxStrategyState mcl = openLong(100.0, 1.0); // helper uses instrument "MCL"
        Candle c = candle(100, 101.5, 100.3, 101.0);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(mcl, c, pointsMnqOnly);
        assertFalse(d.shouldExit());
        // ATR trail (2.0) not the point trail (15) → 101.5 - 2.0 = 99.5
        assertEquals(0, BigDecimal.valueOf(99.5).compareTo(d.updatedTrailingStopPrice()));
    }

    // ── SL_ONLY mode (no trailing ratchet — ride to opposite cross with fixed SL) ───────────────

    @Test
    void slOnly_long_neverArmsTrailing_keepsFixedInitialStop() {
        // ATR=1, slMult=1.4 → fixed stop 98.6. High 101.5 would arm the trail (→99.5) in ATR/POINTS
        // mode; in SL_ONLY the stop must STAY at the fixed initial 98.6 (no ratchet) and not exit.
        WtxStrategyState state = openLong(100.0, 1.0);
        Candle c = candle(100, 101.5, 100.3, 101.0);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(state, c, SL_ONLY);
        assertFalse(d.shouldExit());
        assertEquals(0, BigDecimal.valueOf(98.6).compareTo(d.updatedTrailingStopPrice()));
        // MFE still tracked for display continuity.
        assertEquals(0, BigDecimal.valueOf(101.5).compareTo(d.updatedBestFavorablePrice()));
    }

    @Test
    void slOnly_long_fixedStopHit_isInitialStop() {
        WtxStrategyState state = openLong(100.0, 1.0); // fixed stop = 98.6
        Candle c = candle(100, 100.2, 98.5, 99.0);     // low pierces 98.6
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(state, c, SL_ONLY);
        assertTrue(d.shouldExit());
        assertEquals(WtxAction.CLOSE_LONG, d.exitAction());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.INITIAL_STOP, d.reason());
        assertEquals(0, BigDecimal.valueOf(98.6).compareTo(d.exitPrice()));
    }

    @Test
    void slOnly_short_fixedStopHit_isInitialStop() {
        WtxStrategyState state = openShort(100.0, 1.0); // fixed stop = 101.4
        Candle c = candle(100, 101.5, 99.6, 100.5);     // high pierces 101.4
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(state, c, SL_ONLY);
        assertTrue(d.shouldExit());
        assertEquals(WtxAction.CLOSE_SHORT, d.exitAction());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.INITIAL_STOP, d.reason());
        assertEquals(0, BigDecimal.valueOf(101.4).compareTo(d.exitPrice()));
    }

    // ── Optional fixed take-profit (opt-in; default OFF) ───────────────────────

    // POINTS mode + fixed 20-point TP. 20 < the 30-point trailing activation, so the TP fires while the
    // trail is still disarmed — isolates the TP from trailing interference.
    private static final WtxConfig TP_POINTS = POINTS.withTakeProfit(true, BigDecimal.valueOf(20));

    @Test
    void tp_long_targetHit_isTakeProfit() {
        // TP = entry + 20 = 30020; stop (dynamic 1.4*ATR=14) at 29986 stays clear.
        WtxStrategyState s = openLong(30000, 10);
        Candle c = candle(30000, 30020, 30005, 30015); // high touches TP, low above stop
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, TP_POINTS);
        assertTrue(d.shouldExit());
        assertEquals(WtxAction.CLOSE_LONG, d.exitAction());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.TAKE_PROFIT, d.reason());
        assertEquals(0, BigDecimal.valueOf(30020).compareTo(d.exitPrice()));
        assertEquals(WtxExitType.TAKE_PROFIT, WtxExitType.fromExitReason(d.reason()));
    }

    @Test
    void tp_short_targetHit_isTakeProfit() {
        // Short TP = entry - 20 = 29980; stop at 30014 stays clear.
        WtxStrategyState s = openShort(30000, 10);
        Candle c = candle(30000, 29995, 29980, 29985); // low touches TP, high below stop
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, TP_POINTS);
        assertTrue(d.shouldExit());
        assertEquals(WtxAction.CLOSE_SHORT, d.exitAction());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.TAKE_PROFIT, d.reason());
        assertEquals(0, BigDecimal.valueOf(29980).compareTo(d.exitPrice()));
    }

    @Test
    void tp_disabled_noTakeProfitExit() {
        // Same favorable bar, but TP OFF (default) → no TP exit; trail disarmed, stop clear → no exit at all.
        WtxStrategyState s = openLong(30000, 10);
        Candle c = candle(30000, 30020, 30005, 30015);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, POINTS); // TP off
        assertFalse(d.shouldExit());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.NONE, d.reason());
    }

    @Test
    void tp_stopAndTpSameBar_stopWins_pessimistic() {
        // A bar that spans BOTH the stop (29986) and the TP (30020) must resolve to the STOP (loss),
        // never the TP — pessimistic, matching TradeSimulationService.
        WtxStrategyState s = openLong(30000, 10);
        Candle c = candle(30000, 30025, 29980, 29990); // high >= TP AND low <= stop
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, TP_POINTS);
        assertTrue(d.shouldExit());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.INITIAL_STOP, d.reason()); // stop wins, not TAKE_PROFIT
    }

    @Test
    void tp_atrBased_whenTpPointsZero() {
        // tpPoints=0 → dynamic target = tpAtrMult(2.1)*ATR. ATR=1 → TP = 100 + 2.1 = 102.1.
        WtxConfig tpAtr = CONFIG.withTakeProfit(true, BigDecimal.ZERO);
        WtxStrategyState s = openLong(100.0, 1.0);
        Candle c = candle(100, 102.2, 100.5, 102.0); // high touches 102.1; armed trail (100.2) stays clear of low
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, tpAtr);
        assertTrue(d.shouldExit());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.TAKE_PROFIT, d.reason());
        assertEquals(0, BigDecimal.valueOf(102.1).compareTo(d.exitPrice()));
    }

    @Test
    void tp_slOnlyMode_targetHit_isTakeProfit() {
        // The key integration: in SL_ONLY (the validated profile), an enabled TP must still fire — the
        // SL_ONLY branch must not short-circuit past the take-profit. TP = entry + 20 = 30020; SL at 29986.
        WtxConfig slOnlyTp = SL_ONLY.withTakeProfit(true, BigDecimal.valueOf(20));
        WtxStrategyState s = openLong(30000, 10);
        Candle c = candle(30000, 30020, 30005, 30015);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, slOnlyTp);
        assertTrue(d.shouldExit());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.TAKE_PROFIT, d.reason());
        assertEquals(0, BigDecimal.valueOf(30020).compareTo(d.exitPrice()));
    }

    @Test
    void tp_slOnlyMode_stopBeforeTp_pessimistic() {
        // In SL_ONLY too, a bar spanning both the fixed stop (29986) and TP (30020) resolves to the stop.
        WtxConfig slOnlyTp = SL_ONLY.withTakeProfit(true, BigDecimal.valueOf(20));
        WtxStrategyState s = openLong(30000, 10);
        Candle c = candle(30000, 30025, 29980, 29990);
        WtxTrailingExitEvaluator.Decision d = WtxTrailingExitEvaluator.evaluate(s, c, slOnlyTp);
        assertTrue(d.shouldExit());
        assertEquals(WtxTrailingExitEvaluator.ExitReason.INITIAL_STOP, d.reason());
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
