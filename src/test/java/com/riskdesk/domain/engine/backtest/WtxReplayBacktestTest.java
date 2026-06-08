package com.riskdesk.domain.engine.backtest;

import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxHtfBiasFilter;
import com.riskdesk.domain.engine.strategy.wtx.WtxProfile;
import com.riskdesk.domain.engine.strategy.wtx.WtxTrailingMode;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the faithful replay engine's NEW logic — the loop, the 1m intrabar exit walk, reverse/open/close
 * accounting, and that the live HTF gate + the opt-in TP flow through end-to-end. The signal/exit/HTF maths
 * themselves are covered by {@code WtxBarEvaluatorTest} / {@code WtxTrailingExitEvaluatorTest}.
 *
 * <p>Configs enable {@code useCompra1/useVenta1} so EVERY WaveTrend cross is a signal (a smooth sine rarely
 * reaches the ±53 zone the default {@code compra/venta} require) — this gives the many trades needed to
 * exercise the engine, independent of the signal-zone tuning which is tested elsewhere.</p>
 */
class WtxReplayBacktestTest {

    // Morning ET (10:00 EDT) so no bar falls in the NY force-close window (which would set canTrade=false).
    private static final Instant START = Instant.parse("2026-06-02T14:00:00Z");

    @Test
    void runsAndAccountsCorrectly_onOscillatingSeries() {
        List<WtxReplayBacktest.BarSlice> slices = sineSlices(200, 18000, 18, 18, null);
        WtxReplayBacktest engine = new WtxReplayBacktest(
                signalsAnywhere(WtxConfig.defaults()), WtxProfile.SESSION_ATR, 14, 2.0, 10_000);

        BacktestResult r = engine.run("MNQ", "5m", slices);

        assertTrue(r.totalTrades() >= 3, "an oscillating series with cross-anywhere signals must produce trades");
        assertEquals(slices.size(), r.equityCurve().size(), "one equity point per bar");
        // Accounting invariant: final capital == initial + Σ trade P&L (every close routes through recordClose).
        double sumPnl = r.trades().stream().mapToDouble(BacktestTrade::pnl).sum();
        assertEquals(r.initialCapital() + round2(sumPnl), r.finalCapital(), 0.01);
        assertTrue(r.trades().stream().allMatch(t -> t.side().equals("LONG") || t.side().equals("SHORT")));
    }

    @Test
    void takeProfitFlowsThrough_producesTpExits_andChangesResult() {
        // SL_ONLY so the only exits are the fixed stop, the reverse, or the TP — isolates the TP's effect.
        WtxConfig slOnly = signalsAnywhere(WtxConfig.defaults().withTrailing(
                WtxTrailingMode.SL_ONLY, BigDecimal.valueOf(30), BigDecimal.valueOf(15), BigDecimal.ZERO));
        WtxConfig slOnlyTp = slOnly.withTakeProfit(true, BigDecimal.valueOf(8)); // tight 8pt target (< 18pt swing)

        List<WtxReplayBacktest.BarSlice> slices = sineSlices(200, 18000, 18, 18, null);

        BacktestResult off = new WtxReplayBacktest(slOnly, WtxProfile.SESSION_ATR, 14, 2.0, 10_000)
                .run("MNQ", "5m", slices);
        BacktestResult on = new WtxReplayBacktest(slOnlyTp, WtxProfile.SESSION_ATR, 14, 2.0, 10_000)
                .run("MNQ", "5m", slices);

        assertTrue(on.trades().stream().anyMatch(t -> "TAKE_PROFIT".equals(t.exitReason())),
                "with a tight TP enabled, at least one trade must exit via TAKE_PROFIT");
        assertFalse(off.trades().stream().anyMatch(t -> "TAKE_PROFIT".equals(t.exitReason())),
                "with TP off, no trade should carry a TAKE_PROFIT exit");
        assertTrue(off.finalCapital() != on.finalCapital(), "enabling the TP must change the P&L");
    }

    @Test
    void htfBearish_blocksLongEntries() {
        // HTF profile + a BEARISH 1h context for every bar → every LONG signal is filtered (action NONE),
        // so the book never opens a long. This is the live "stuck short, LONG shows NONE" scenario.
        WtxHtfBiasFilter.HtfBiasContext bearish = new WtxHtfBiasFilter.HtfBiasContext(
                BigDecimal.valueOf(100), BigDecimal.valueOf(110), BigDecimal.valueOf(120)); // close<=fast<=slow → BEARISH
        List<WtxReplayBacktest.BarSlice> slices = sineSlices(200, 18000, 18, 18, bearish);

        BacktestResult r = new WtxReplayBacktest(signalsAnywhere(WtxConfig.defaults()), WtxProfile.HTF, 14, 2.0, 10_000)
                .run("MNQ", "5m", slices);

        assertTrue(r.totalTrades() >= 1, "shorts should still fire under a bearish HTF");
        assertTrue(r.trades().stream().noneMatch(t -> "LONG".equals(t.side())),
                "HTF BEARISH must gate every long — no LONG position should ever open");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Copy of {@code base} with {@code useCompra1/useVenta1} enabled so EVERY WaveTrend cross is actionable
     *  (rebuilds the record via accessors — there is no production wither for the signal-type flags). */
    private static WtxConfig signalsAnywhere(WtxConfig b) {
        return new WtxConfig(
                b.instruments(), b.timeframes(), b.n1(), b.n2(), b.signalPeriod(), b.nsc(), b.nsv(),
                b.useCompra(), true, b.useVenta(), true, b.reverseOnOpp(), b.fixedQty(), b.maxDailyLossUsd(),
                b.forceCloseNy(), b.nySessionEndHour(), b.nySessionEndMin(), b.closeBeforeMin(), b.atrLength(),
                b.slAtrMult(), b.tpAtrMult(), b.trailingAtrMult(), b.trailingActivationR(), b.htfTimeframe(),
                b.htfFastLen(), b.htfSlowLen(), b.structureLookback(), b.sweepBufferAtr(), b.trailingMode(),
                b.trailingActivationPoints(), b.trailingPoints(), b.slPoints(), b.dailyResetEnabled(),
                b.trailingPointsInstruments(), b.sessionFilterEnabled(), b.sessionBlockStartMinEt(),
                b.sessionBlockEndMinEt(), b.takeProfitEnabled(), b.tpPoints());
    }

    /** A sine-wave OHLC series (one 1m candle per bar) that warms up WaveTrend and oscillates enough to
     *  produce crossovers. {@code htfCtx} is attached to every slice (null when the profile needs no HTF). */
    private static List<WtxReplayBacktest.BarSlice> sineSlices(int n, double base, double amp, int period,
                                                               WtxHtfBiasFilter.HtfBiasContext htfCtx) {
        List<WtxReplayBacktest.BarSlice> out = new ArrayList<>(n);
        double prevClose = base;
        for (int i = 0; i < n; i++) {
            double close = base + amp * Math.sin(2 * Math.PI * i / period);
            double open = prevClose;
            double high = Math.max(open, close) + 2;
            double low = Math.min(open, close) - 2;
            Instant ts = START.plusSeconds(60L * i);
            Candle bar = new Candle(Instrument.MNQ, "5m", ts,
                    BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                    BigDecimal.valueOf(low), BigDecimal.valueOf(close), 100L);
            out.add(new WtxReplayBacktest.BarSlice(bar, List.of(bar), htfCtx));
            prevClose = close;
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
