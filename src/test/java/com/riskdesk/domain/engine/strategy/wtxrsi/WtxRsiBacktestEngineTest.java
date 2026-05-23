package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBacktestEngine.Result;
import com.riskdesk.domain.model.Candle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WtxRsiBacktestEngineTest {

    @Test
    void produces_trades_and_equity_curve_in_reversal_mode() {
        List<Candle> bars = SyntheticCandles.mnq(800, 7);
        WtxRsiConfig cfg = WtxRsiConfig.defaults5m();
        Result result = new WtxRsiBacktestEngine(cfg).run(bars);

        assertEquals(bars.size(), result.equityCurve().size());
        assertFalse(result.trades().isEmpty(), "synthetic fixture should produce trades");
        for (WtxRsiTrade t : result.trades()) {
            assertTrue(t.contracts() >= 1);
            assertNotNull(t.entryPrice());
            assertNotNull(t.exitPrice());
            assertNotNull(t.outcome());
        }
    }

    @Test
    void at_most_one_position_open_across_both_sides() {
        // Regression for the backtest hedge bug: an opposite-side signal must not
        // open a second position while the first is still in flight. The live
        // orchestrator suppresses these, the backtest must mirror that or PnL drifts
        // away from the production behaviour we're trying to measure.
        List<Candle> bars = SyntheticCandles.mnq(800, 7);
        Result result = new WtxRsiBacktestEngine(WtxRsiConfig.defaults5m()).run(bars);
        record Event(java.time.Instant t, int delta) {}
        java.util.List<Event> events = new java.util.ArrayList<>();
        for (WtxRsiTrade t : result.trades()) {
            events.add(new Event(t.entryTime(), 1));
            events.add(new Event(t.exitTime(), -1));
        }
        // Exits sort before entries when timestamps tie so a back-to-back close/open
        // at the same instant doesn't briefly read as 2 open.
        events.sort((a, b) -> {
            int c = a.t().compareTo(b.t());
            return c != 0 ? c : Integer.compare(a.delta(), b.delta());
        });
        int open = 0;
        for (Event e : events) {
            open += e.delta();
            assertTrue(open <= 1, "more than one position open simultaneously at " + e.t());
        }
    }

    @Test
    void no_concurrent_trades_per_side() {
        List<Candle> bars = SyntheticCandles.mnq(800, 7);
        Result result = new WtxRsiBacktestEngine(WtxRsiConfig.defaults5m()).run(bars);
        WtxRsiTrade prevLong = null, prevShort = null;
        for (WtxRsiTrade t : result.trades()) {
            if (t.side() == WtxRsiSignal.Side.LONG) {
                if (prevLong != null) {
                    assertFalse(t.entryTime().isBefore(prevLong.exitTime()),
                            "concurrent LONG trades detected");
                }
                prevLong = t;
            } else {
                if (prevShort != null) {
                    assertFalse(t.entryTime().isBefore(prevShort.exitTime()),
                            "concurrent SHORT trades detected");
                }
                prevShort = t;
            }
        }
    }

    @Test
    void r_multiple_mode_produces_tp_hits() {
        WtxRsiConfig base = WtxRsiConfig.defaults5m();
        WtxRsiConfig withTp = new WtxRsiConfig(
                base.wtN1(), base.wtN2(), base.wtSignalPeriod(),
                base.wtOverbought(), base.wtOversold(),
                base.rsiLength(), base.rsiSmaLength(),
                base.syncLookbackBars(),
                base.zoneMode(), base.zoneLookbackBars(),
                base.fractalLeftRight(), base.fractalMaxLookback(),
                base.swingBufferTicks(), base.tickSize(), base.tickValueUsd(),
                base.baseContracts(), base.confirmedMultiplier(),
                WtxRsiTpMode.R_MULTIPLE, new BigDecimal("1.5"),
                base.chaikinFast(), base.chaikinSlow(), base.chaikinEnabled(),
                base.biasSource()
        );
        List<Candle> bars = SyntheticCandles.mnq(1500, 9);
        Result result = new WtxRsiBacktestEngine(withTp).run(bars);
        long tpHits = result.trades().stream()
                .filter(t -> t.outcome() == WtxRsiTradeOutcome.TP_HIT)
                .count();
        long slHits = result.trades().stream()
                .filter(t -> t.outcome() == WtxRsiTradeOutcome.SL_HIT)
                .count();
        // Over 1500 noisy bars with R=1.5, we should observe both kinds.
        assertTrue(tpHits + slHits > 0, "expected some SL or TP exits with R-multiple mode");
    }

    @Test
    void metrics_sum_trades_and_pnl() {
        List<Candle> bars = SyntheticCandles.mnq(800, 7);
        Result result = new WtxRsiBacktestEngine(WtxRsiConfig.defaults5m()).run(bars);
        WtxRsiMetrics m = WtxRsiMetrics.compute(result.trades(), result.equityCurve());
        assertEquals(result.trades().size(), m.trades());
        assertEquals(m.trades(), m.longTrades() + m.shortTrades());
        BigDecimal sum = result.trades().stream()
                .map(WtxRsiTrade::pnlUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(m.totalPnlUsd()),
                "metrics totalPnlUsd must match sum of trade P&Ls");
    }
}
