package com.riskdesk.domain.engine.backtest;

import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator;
import com.riskdesk.domain.engine.indicators.WaveTrendIndicator.WaveTrendResult;
import com.riskdesk.domain.engine.strategy.wtx.WtxBarEvaluator;
import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxHtfBiasFilter;
import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxProfile;
import com.riskdesk.domain.engine.strategy.wtx.WtxSignal;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.engine.strategy.wtx.WtxTrailingExitEvaluator;
import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Faithful WTX backtest — replays the SAME production decisions over historical candles, so an exit/entry
 * tuning (e.g. the opt-in fixed take-profit) is measured on exactly the logic that runs live:
 * <ul>
 *   <li><b>entries / reverses</b>: {@link WtxBarEvaluator} (incl. the HTF-bias gate via
 *       {@link WtxHtfBiasFilter}) evaluated at the trading-timeframe bar CLOSE — like the live
 *       {@code @EventListener(CandleClosed)} path;</li>
 *   <li><b>exits</b>: {@link WtxTrailingExitEvaluator} (initial stop / trailing / take-profit) evaluated on
 *       the REAL 1-minute intrabar path, not the trading-timeframe OHLC. PR #418 showed OHLC overstates
 *       tight exits because it hides the within-bar order of SL vs TP; walking the 1m candles gives the true
 *       order and the pessimistic same-bar resolution the evaluator already encodes.</li>
 * </ul>
 *
 * <p>Unlike {@link WaveTrendBacktest} (a divergent, self-contained WT engine) this reuses the production
 * evaluators, so its P&amp;L reflects the live HTF / {@code SL_ONLY} strategy and is the right tool to decide
 * whether enabling the take-profit helps. Scope: signals + HTF gate + SL/trailing/TP exits + reverse-on-opp.
 * <b>Not modelled</b> (they affect both A/B arms similarly, out of scope for an exit-rule's marginal effect):
 * the NY force-close flatten and the daily max-loss latch — though the bar evaluator still suppresses NEW
 * entries inside the force-close window (canTrade=false), matching live. The STRICT-profile structure filter
 * is not modelled (pass an HTF or lower profile).</p>
 */
public final class WtxReplayBacktest {

    /**
     * One trading-timeframe bar, the 1-minute candles that compose it (ordered, oldest first — used for the
     * intrabar exit walk), and the HTF-bias context as of that bar ({@code null} when the profile needs no
     * HTF filter). The service builds these from the stored 1m candles; the engine stays pure.
     */
    public record BarSlice(Candle bar, List<Candle> minutes, WtxHtfBiasFilter.HtfBiasContext htfCtx) {}

    private final WtxConfig config;
    private final WtxProfile profile;
    private final int atrLength;
    private final double pointValue;
    private final double startEquity;

    public WtxReplayBacktest(WtxConfig config, WtxProfile profile, int atrLength,
                             double pointValue, double startEquity) {
        this.config = config;
        this.profile = profile;
        this.atrLength = atrLength;
        this.pointValue = pointValue;
        this.startEquity = startEquity;
    }

    /** Mutable open-position bookkeeping kept alongside the immutable {@link WtxStrategyState}. */
    private static final class Open {
        String side;          // "LONG" / "SHORT"
        double entryPrice;
        Instant entryTime;
        double qty;
        boolean isOpen() { return side != null; }
    }

    public BacktestResult run(String instrument, String timeframe, List<BarSlice> slices) {
        if (slices == null || slices.isEmpty()) {
            return BacktestResult.empty("WT_X Faithful Replay", instrument, timeframe, startEquity);
        }
        List<Candle> bars = new ArrayList<>(slices.size());
        for (BarSlice s : slices) bars.add(s.bar());
        List<WaveTrendResult> wt = new WaveTrendIndicator(config.n1(), config.n2(), config.signalPeriod())
                .calculate(bars);
        // calculate() skips the warmup bars (no result until the signal SMA window fills), so its result list
        // is SHORTER than bars and starts at bar index = bars.size() - wt.size(). WaveTrend is causal (EMA/SMA
        // over past bars only), so wt.get(i - wtOffset) equals the value the live service computes at bar i
        // (it calls calculate on the prefix and takes the last two). Map bar → wt index by this offset.
        int wtOffset = bars.size() - wt.size();

        WtxStrategyState state = WtxStrategyState.initial(
                instrument, timeframe, BigDecimal.valueOf(startEquity)).withProfile(profile);
        Open open = new Open();

        List<BacktestTrade> trades = new ArrayList<>();
        List<Double> equityCurve = new ArrayList<>();
        double equity = startEquity;
        double peak = startEquity;
        double maxDrawdown = 0;

        for (int i = 0; i < slices.size(); i++) {
            BarSlice slice = slices.get(i);
            Candle bar = slice.bar();

            // 1) Intrabar exit on THIS bar's 1m path. A position opened at a PRIOR bar's close is live during
            //    these minutes; one opened at THIS bar's close (below) is first checked next bar — matching
            //    the "close-on-close entry never stops on its own bar" invariant.
            if (state.currentPosition() != WtxPosition.FLAT) {
                for (Candle minute : slice.minutes()) {
                    WtxTrailingExitEvaluator.Decision ex = WtxTrailingExitEvaluator.evaluate(state, minute, config);
                    if (ex.shouldExit()) {
                        equity += recordClose(trades, open, ex.exitPrice().doubleValue(),
                                minute.getTimestamp(), ex.reason().name());
                        state = state.withFlat(BigDecimal.ZERO);
                        break;
                    }
                    state = state.withTrailing(ex.updatedBestFavorablePrice(), ex.updatedTrailingStopPrice());
                }
            }

            // 2) Signal evaluation at the bar CLOSE — needs prev + curr WaveTrend (skip the warmup bars).
            int wi = i - wtOffset;
            if (wi >= 1) {
                WaveTrendResult prev = wt.get(wi - 1);
                WaveTrendResult curr = wt.get(wi);
                // Prelim (no filters) only to read the direction, then compute the HTF decision FOR that
                // direction — exactly the live WtxStrategyService two-pass sequence.
                Optional<WtxSignal> prelim = WtxBarEvaluator.evaluate(
                        prev, curr, config, state, bar.getTimestamp(), timeframe, null, null);
                WtxHtfBiasFilter.Decision htfDecision = null;
                if (prelim.isPresent() && profile.requiresHtfFilter()) {
                    htfDecision = WtxHtfBiasFilter.evaluate(prelim.get().direction(), slice.htfCtx());
                }
                Optional<WtxSignal> maybe = WtxBarEvaluator.evaluate(
                        prev, curr, config, state, bar.getTimestamp(), timeframe, htfDecision, null);

                if (maybe.isPresent()) {
                    double close = bar.getClose().doubleValue();
                    Instant ts = bar.getTimestamp();
                    switch (maybe.get().suggestedAction()) {
                        case REVERSE_TO_LONG -> {
                            equity += recordClose(trades, open, close, ts, "SIGNAL_REVERSE");
                            state = openPosition(state, open, WtxPosition.LONG, close, ts, bars, i);
                        }
                        case REVERSE_TO_SHORT -> {
                            equity += recordClose(trades, open, close, ts, "SIGNAL_REVERSE");
                            state = openPosition(state, open, WtxPosition.SHORT, close, ts, bars, i);
                        }
                        case OPEN_LONG ->
                            state = openPosition(state, open, WtxPosition.LONG, close, ts, bars, i);
                        case OPEN_SHORT ->
                            state = openPosition(state, open, WtxPosition.SHORT, close, ts, bars, i);
                        case CLOSE_LONG, CLOSE_SHORT -> {
                            if (open.isOpen()) {
                                equity += recordClose(trades, open, close, ts, "CLOSE_SIGNAL");
                                state = state.withFlat(BigDecimal.ZERO);
                            }
                        }
                        case NONE -> { /* informational only — no order */ }
                    }
                }
            }

            // 3) Equity curve point: realized equity + open-position unrealized at this bar's close.
            double unrealized = open.isOpen()
                    ? pnl(open.side, open.entryPrice, bar.getClose().doubleValue(), open.qty) : 0;
            double mark = equity + unrealized;
            equityCurve.add(round2(mark));
            peak = Math.max(peak, mark);
            maxDrawdown = Math.max(maxDrawdown, peak - mark);
        }

        // Close any position still open at the end of data, at the last bar's close.
        if (open.isOpen()) {
            Candle last = bars.get(bars.size() - 1);
            equity += recordClose(trades, open, last.getClose().doubleValue(), last.getTimestamp(), "END_OF_DATA");
        }

        return buildResult(instrument, timeframe, bars.size(), equity, maxDrawdown, peak, trades, equityCurve);
    }

    /**
     * Record a close trade for the open position at {@code exitPrice} and return its P&amp;L (0 when nothing
     * was open, so callers can {@code equity +=} unconditionally). Clears the open marker.
     */
    private double recordClose(List<BacktestTrade> trades, Open open, double exitPrice, Instant time,
                               String reason) {
        if (!open.isOpen()) {
            return 0;
        }
        // Accumulate the ROUNDED P&L into equity so the final capital and the equity curve match the sum of
        // the per-trade P&L shown to the user exactly (no sub-cent drift between the trade list and equity).
        double pnl = round2(pnl(open.side, open.entryPrice, exitPrice, open.qty));
        trades.add(new BacktestTrade(trades.size() + 1, open.side, round2(open.entryPrice), open.entryTime,
                round2(exitPrice), time, open.qty, pnl, round2(pnl / startEquity * 100), reason));
        open.side = null;
        return pnl;
    }

    /** Open a fresh position at {@code price}; entry ATR is computed on the bar prefix (live convention). */
    private WtxStrategyState openPosition(WtxStrategyState state, Open open, WtxPosition pos, double price,
                                          Instant time, List<Candle> bars, int barIndex) {
        BigDecimal atr = AtrCalculator.compute(bars.subList(0, barIndex + 1), atrLength);
        double qty = config.fixedQty() != null ? config.fixedQty().doubleValue() : 1.0;
        open.side = pos == WtxPosition.LONG ? "LONG" : "SHORT";
        open.entryPrice = price;
        open.entryTime = time;
        open.qty = qty;
        return state.withPosition(pos, BigDecimal.valueOf(price), BigDecimal.valueOf(qty), atr);
    }

    private double pnl(String side, double entry, double exit, double qty) {
        double pts = "LONG".equals(side) ? (exit - entry) : (entry - exit);
        return pts * qty * pointValue;
    }

    private BacktestResult buildResult(String instrument, String timeframe, int totalBars, double equity,
                                       double maxDrawdown, double peak, List<BacktestTrade> trades,
                                       List<Double> equityCurve) {
        int wins = 0, losses = 0;
        double totalWin = 0, totalLoss = 0;
        List<Double> returns = new ArrayList<>();
        for (BacktestTrade t : trades) {
            if (t.pnl() > 0) { wins++; totalWin += t.pnl(); }
            else { losses++; totalLoss += Math.abs(t.pnl()); }
            returns.add(t.pnl());
        }
        double winRate = trades.isEmpty() ? 0 : (double) wins / trades.size() * 100;
        double avgWin = wins > 0 ? totalWin / wins : 0;
        double avgLoss = losses > 0 ? totalLoss / losses : 0;
        double profitFactor = totalLoss > 0 ? totalWin / totalLoss : (totalWin > 0 ? Double.MAX_VALUE : 0);
        double totalPnl = equity - startEquity;
        double maxDdPct = peak > 0 ? maxDrawdown / peak * 100 : 0;
        double sharpe = 0;
        if (returns.size() > 1) {
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = returns.stream().mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0);
            double std = Math.sqrt(variance);
            sharpe = std > 0 ? mean / std * Math.sqrt(returns.size()) : 0;
        }
        String name = "WT_X Faithful Replay (" + profile + (config.takeProfitEnabled() ? " +TP" : "") + ")";
        return new BacktestResult(
                name, instrument, timeframe, totalBars,
                round2(startEquity), round2(equity), round2(totalPnl), round2(totalPnl / startEquity * 100),
                trades.size(), wins, losses, round2(winRate), round2(avgWin), round2(avgLoss),
                round2(profitFactor), round2(maxDrawdown), round2(maxDdPct), round2(sharpe),
                trades, equityCurve, List.of(), null, List.of());
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
