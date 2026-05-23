package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-domain backtest. No Spring, no JPA, no IBKR.
 *
 * <p>Execution model:
 * <ul>
 *   <li>Indicators are computed once over the full series.</li>
 *   <li>Signals fire on the close of bar i; entries fill at the OPEN of bar i+1.</li>
 *   <li>SL/TP are evaluated intra-bar with the pessimistic rule:
 *       if both SL and TP are touched in the same bar, SL wins.
 *       (Matches the production {@code TradeSimulationService} convention.)</li>
 *   <li>{@link WtxRsiTpMode#REVERSAL}: the position is closed when the opposite
 *       WT signal fires on its <i>own</i> emit bar (next bar's open is used as
 *       the exit fill price).</li>
 *   <li>One trade max per side concurrently. New signals on the same side are
 *       dropped while a position is open.</li>
 * </ul>
 */
public final class WtxRsiBacktestEngine {

    private final WtxRsiConfig config;

    public WtxRsiBacktestEngine(WtxRsiConfig config) {
        this.config = config;
    }

    public Result run(List<Candle> candles) {
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

        List<WtxRsiTrade> trades = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>();
        OpenPosition longPos = null;
        OpenPosition shortPos = null;
        BigDecimal equity = BigDecimal.ZERO;

        // Pending entries — signal at bar i, filled at open of bar i+1
        WtxRsiSignal pendingLong = null;
        WtxRsiSignal pendingShort = null;

        for (int i = 0; i < candles.size(); i++) {
            Candle bar = candles.get(i);

            // ── 1) Fill pending entries at the open of this bar ──────────────
            if (pendingLong != null) {
                OpenPosition opened = openPosition(pendingLong, candles, bar.getOpen());
                if (opened != null && longPos == null) longPos = opened;
                pendingLong = null;
            }
            if (pendingShort != null) {
                OpenPosition opened = openPosition(pendingShort, candles, bar.getOpen());
                if (opened != null && shortPos == null) shortPos = opened;
                pendingShort = null;
            }

            // ── 2) Check exits intra-bar (SL / TP) ───────────────────────────
            if (longPos != null) {
                ExitResult exit = checkExit(longPos, bar);
                if (exit != null) {
                    closeTrade(trades, longPos, bar.getTimestamp(), exit.exitPrice(), exit.outcome());
                    equity = equity.add(pnl(longPos, exit.exitPrice()));
                    longPos = null;
                }
            }
            if (shortPos != null) {
                ExitResult exit = checkExit(shortPos, bar);
                if (exit != null) {
                    closeTrade(trades, shortPos, bar.getTimestamp(), exit.exitPrice(), exit.outcome());
                    equity = equity.add(pnl(shortPos, exit.exitPrice()));
                    shortPos = null;
                }
            }

            // ── 3) Reversal exits (TP mode REVERSAL only) ────────────────────
            if (config.tpMode() == WtxRsiTpMode.REVERSAL) {
                WtxRsiSignal sig = signalsByBar.get(i);
                if (sig != null) {
                    // Opposite signal closes the open trade at next bar's open.
                    if (sig.side() == WtxRsiSignal.Side.SHORT && longPos != null) {
                        BigDecimal exitPx = nextOpen(candles, i);
                        if (exitPx != null) {
                            closeTrade(trades, longPos, candles.get(Math.min(i + 1, candles.size() - 1)).getTimestamp(),
                                    exitPx, WtxRsiTradeOutcome.REVERSAL_EXIT);
                            equity = equity.add(pnl(longPos, exitPx));
                            longPos = null;
                        }
                    } else if (sig.side() == WtxRsiSignal.Side.LONG && shortPos != null) {
                        BigDecimal exitPx = nextOpen(candles, i);
                        if (exitPx != null) {
                            closeTrade(trades, shortPos, candles.get(Math.min(i + 1, candles.size() - 1)).getTimestamp(),
                                    exitPx, WtxRsiTradeOutcome.REVERSAL_EXIT);
                            equity = equity.add(pnl(shortPos, exitPx));
                            shortPos = null;
                        }
                    }
                }
            }

            equityCurve.add(new EquityPoint(bar.getTimestamp(), equity));

            // ── 4) Queue new entries (cannot pyramid on same side) ───────────
            WtxRsiSignal sig = signalsByBar.get(i);
            if (sig != null) {
                if (sig.side() == WtxRsiSignal.Side.LONG && longPos == null && pendingLong == null) {
                    pendingLong = sig;
                } else if (sig.side() == WtxRsiSignal.Side.SHORT && shortPos == null && pendingShort == null) {
                    pendingShort = sig;
                }
            }
        }

        // Force-close still-open positions at the last bar's close.
        Candle last = candles.get(candles.size() - 1);
        if (longPos != null) {
            closeTrade(trades, longPos, last.getTimestamp(), last.getClose(), WtxRsiTradeOutcome.END_OF_SERIES);
            equity = equity.add(pnl(longPos, last.getClose()));
        }
        if (shortPos != null) {
            closeTrade(trades, shortPos, last.getTimestamp(), last.getClose(), WtxRsiTradeOutcome.END_OF_SERIES);
            equity = equity.add(pnl(shortPos, last.getClose()));
        }
        if (!equityCurve.isEmpty()) {
            equityCurve.set(equityCurve.size() - 1, new EquityPoint(last.getTimestamp(), equity));
        }

        return new Result(allSignals, trades, equityCurve);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private OpenPosition openPosition(WtxRsiSignal sig, List<Candle> candles, BigDecimal entryPrice) {
        return WtxRsiRiskCalculator.build(sig, candles, entryPrice, config)
                .map(plan -> new OpenPosition(sig, plan, plan.entryPrice()))
                .orElse(null);
    }

    private ExitResult checkExit(OpenPosition pos, Candle bar) {
        boolean isLong = pos.signal.side() == WtxRsiSignal.Side.LONG;
        BigDecimal sl = pos.plan.stopLoss();
        BigDecimal tp = pos.plan.takeProfit();
        BigDecimal high = bar.getHigh();
        BigDecimal low = bar.getLow();

        boolean slHit = isLong ? low.compareTo(sl) <= 0 : high.compareTo(sl) >= 0;
        boolean tpHit = tp != null && (isLong ? high.compareTo(tp) >= 0 : low.compareTo(tp) <= 0);

        if (slHit) return new ExitResult(sl, WtxRsiTradeOutcome.SL_HIT);
        if (tpHit) return new ExitResult(tp, WtxRsiTradeOutcome.TP_HIT);
        return null;
    }

    private void closeTrade(
            List<WtxRsiTrade> trades, OpenPosition pos, java.time.Instant exitTime,
            BigDecimal exitPrice, WtxRsiTradeOutcome outcome) {
        BigDecimal direction = pos.signal.side() == WtxRsiSignal.Side.LONG
                ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal pnlPoints = exitPrice.subtract(pos.plan.entryPrice()).multiply(direction);
        BigDecimal pnlUsd = pnl(pos, exitPrice);
        trades.add(new WtxRsiTrade(
                pos.signal.side(),
                pos.signal.timestamp(),
                pos.plan.entryPrice(),
                exitTime,
                exitPrice,
                pos.plan.contracts(),
                pos.plan.stopLoss(),
                pos.plan.takeProfit(),
                outcome,
                pnlPoints,
                pnlUsd
        ));
    }

    private BigDecimal pnl(OpenPosition pos, BigDecimal exitPrice) {
        BigDecimal direction = pos.signal.side() == WtxRsiSignal.Side.LONG
                ? BigDecimal.ONE : BigDecimal.ONE.negate();
        BigDecimal pointsPerContract = exitPrice.subtract(pos.plan.entryPrice()).multiply(direction);
        BigDecimal ticks = pointsPerContract.divide(config.tickSize(), 4, RoundingMode.HALF_UP);
        return ticks.multiply(config.tickValueUsd())
                .multiply(BigDecimal.valueOf(pos.plan.contracts()));
    }

    private BigDecimal nextOpen(List<Candle> candles, int i) {
        if (i + 1 >= candles.size()) return null;
        return candles.get(i + 1).getOpen();
    }

    // ── value types ────────────────────────────────────────────────────────

    public record EquityPoint(java.time.Instant timestamp, BigDecimal equityUsd) {}

    public record Result(
            List<WtxRsiSignal> signals,
            List<WtxRsiTrade> trades,
            List<EquityPoint> equityCurve
    ) {}

    private record ExitResult(BigDecimal exitPrice, WtxRsiTradeOutcome outcome) {}

    private static final class OpenPosition {
        final WtxRsiSignal signal;
        final WtxRsiRiskPlan plan;
        final BigDecimal entryPrice;
        OpenPosition(WtxRsiSignal signal, WtxRsiRiskPlan plan, BigDecimal entryPrice) {
            this.signal = signal; this.plan = plan; this.entryPrice = entryPrice;
        }
    }
}
