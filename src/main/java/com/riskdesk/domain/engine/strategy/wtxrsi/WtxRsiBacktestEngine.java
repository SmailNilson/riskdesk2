package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure-domain backtest. No Spring, no JPA, no IBKR.
 *
 * <p><b>Single source of truth.</b> This engine no longer re-implements the
 * open / reverse / suppress / SL-TP logic — it drives the very same
 * {@link WtxRsiTransition#reduce} finite-state machine the live orchestrator
 * ({@code WtxRsiStrategyService}) uses. Feeding identical candles to both
 * therefore yields an identical trade sequence by construction; the two can no
 * longer silently diverge.
 *
 * <p>Execution model (aligned with the live engine):
 * <ul>
 *   <li>Indicators are computed once over the full series.</li>
 *   <li>Entries fill at the <b>close of the signal bar</b> ({@code signal.close()})
 *       — the canonical fill model shared with the live path.</li>
 *   <li>SL/TP are evaluated intra-bar with the pessimistic rule (SL wins on a
 *       same-bar double touch), starting on the bar <i>after</i> the entry.</li>
 *   <li>{@link WtxRsiTpMode#REVERSAL}: an opposite signal closes at its own close
 *       and immediately attempts the reverse entry.</li>
 *   <li>One position at a time; the chaikin-required gate and (optionally) the
 *       swing-bias filter apply exactly as live.</li>
 *   <li>A position still open at the end is force-closed at the last close
 *       ({@link WtxRsiTradeOutcome#END_OF_SERIES}).</li>
 * </ul>
 */
public final class WtxRsiBacktestEngine {

    private static final String BACKTEST_INSTRUMENT = "BACKTEST";
    private static final String BACKTEST_TIMEFRAME = "";

    private final WtxRsiConfig config;

    public WtxRsiBacktestEngine(WtxRsiConfig config) {
        this.config = config;
    }

    /** Run with the swing-bias filter off (the live default). */
    public Result run(List<Candle> candles) {
        return run(candles, false);
    }

    /**
     * Run with an explicit swing-bias filter toggle. Bias is resolved per bar
     * with the pure {@code FRACTAL_HH_HL} detector — the {@code SMC_ENGINE}
     * source is an application-layer dependency and is not replayable here.
     */
    public Result run(List<Candle> candles, boolean swingBiasFilterEnabled) {
        if (candles == null || candles.isEmpty()) {
            return new Result(List.of(), List.of(), List.of());
        }

        WtxRsiBarEvaluator.IndicatorSeries series = WtxRsiBarEvaluator.computeIndicators(candles, config);

        // Map signals to their emit bar for O(1) lookup.
        Map<Integer, WtxRsiSignal> signalsByBar = new HashMap<>();
        List<WtxRsiSignal> allSignals = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            WtxRsiBarEvaluator.evaluate(candles, series, i, config).ifPresent(s -> {
                signalsByBar.put(s.barIndex(), s);
                allSignals.add(s);
            });
        }

        WtxRsiStrategyState state = WtxRsiStrategyState.initial(
                BACKTEST_INSTRUMENT, BACKTEST_TIMEFRAME, config.chaikinRequired());
        if (swingBiasFilterEnabled) {
            state = state.withSwingBiasFilter(true);
        }

        List<WtxRsiTrade> trades = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>();
        BigDecimal equity = BigDecimal.ZERO;
        OpenContext open = null; // remembers the entry signal + plan for trade building

        for (int i = 0; i < candles.size(); i++) {
            Candle bar = candles.get(i);
            Optional<WtxRsiSignal> signal = Optional.ofNullable(signalsByBar.get(i));
            WtxRsiSwingBias bias = swingBiasFilterEnabled
                    ? WtxRsiSwingBiasDetector.detect(
                            candles.subList(0, i + 1), config.fractalLeftRight(), config.fractalMaxLookback())
                    : WtxRsiSwingBias.NEUTRAL;

            WtxRsiTransition.Result r =
                    WtxRsiTransition.reduce(state, bar, candles, signal, bias, config);

            for (WtxRsiDecision decision : r.decisions()) {
                if (decision instanceof WtxRsiDecision.Open o) {
                    open = new OpenContext(o.signal(), o.plan());
                } else if (decision instanceof WtxRsiDecision.Close c) {
                    if (open != null) {
                        WtxRsiTrade trade = buildTrade(open, c.exitPrice(), c.timestamp(),
                                c.realizedPnl(), mapOutcome(c.cause()), open.plan().contracts());
                        trades.add(trade);
                        equity = equity.add(trade.pnlUsd());
                        open = null;
                    }
                }
                // Suppress / Block / Reject produce no trade.
            }

            state = r.newState();
            equityCurve.add(new EquityPoint(bar.getTimestamp(), equity));
        }

        // Force-close a position still open at the last bar's close.
        if (state.currentPosition() != WtxRsiPosition.FLAT && open != null) {
            Candle last = candles.get(candles.size() - 1);
            BigDecimal exitPrice = last.getClose();
            BigDecimal realized = WtxRsiTransition.realizedPnl(state, exitPrice, config);
            WtxRsiTrade trade = buildTrade(open, exitPrice, last.getTimestamp(),
                    realized, WtxRsiTradeOutcome.END_OF_SERIES, open.plan().contracts());
            trades.add(trade);
            equity = equity.add(trade.pnlUsd());
            if (!equityCurve.isEmpty()) {
                equityCurve.set(equityCurve.size() - 1, new EquityPoint(last.getTimestamp(), equity));
            }
        }

        return new Result(allSignals, trades, equityCurve);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private WtxRsiTrade buildTrade(
            OpenContext open, BigDecimal exitPrice, java.time.Instant exitTime,
            BigDecimal pnlUsd, WtxRsiTradeOutcome outcome, int contracts) {
        WtxRsiSignal entrySignal = open.signal();
        WtxRsiRiskPlan plan = open.plan();
        BigDecimal direction = entrySignal.side() == WtxRsiSignal.Side.LONG
                ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal pnlPoints = exitPrice.subtract(plan.entryPrice()).multiply(direction);
        return new WtxRsiTrade(
                entrySignal.side(),
                entrySignal.timestamp(),
                plan.entryPrice(),
                exitTime,
                exitPrice,
                contracts,
                plan.stopLoss(),
                plan.takeProfit(),
                outcome,
                pnlPoints,
                pnlUsd);
    }

    /**
     * Maps the reducer's {@link WtxRsiDecision.CloseCause} onto the trade-ledger
     * outcome. {@code BIAS_FLIP} has no dedicated ledger outcome and can only
     * occur when the swing-bias filter is enabled; it is reported as a
     * reversal-style exit.
     */
    private static WtxRsiTradeOutcome mapOutcome(WtxRsiDecision.CloseCause cause) {
        return switch (cause) {
            case STOP_LOSS -> WtxRsiTradeOutcome.SL_HIT;
            case TAKE_PROFIT -> WtxRsiTradeOutcome.TP_HIT;
            case REVERSAL, BIAS_FLIP -> WtxRsiTradeOutcome.REVERSAL_EXIT;
        };
    }

    // ── value types ────────────────────────────────────────────────────────

    public record EquityPoint(java.time.Instant timestamp, BigDecimal equityUsd) {}

    public record Result(
            List<WtxRsiSignal> signals,
            List<WtxRsiTrade> trades,
            List<EquityPoint> equityCurve
    ) {}

    private record OpenContext(WtxRsiSignal signal, WtxRsiRiskPlan plan) {}
}
