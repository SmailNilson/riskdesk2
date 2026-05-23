package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBacktestEngine.EquityPoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Summary metrics over a closed-trade ledger and equity curve.
 *
 * {@code maxDrawdownPct} stays at zero when the running equity peak never
 * climbs above zero — a percentage requires a positive principal to be meaningful,
 * and the simulated account starts at 0 (cash futures, margin-based).
 */
public record WtxRsiMetrics(
        int trades,
        int wins,
        int losses,
        BigDecimal winRate,
        BigDecimal avgWinUsd,
        BigDecimal avgLossUsd,
        BigDecimal profitFactor,
        BigDecimal expectancyUsd,
        BigDecimal totalPnlUsd,
        BigDecimal maxDrawdownUsd,
        BigDecimal maxDrawdownPct,
        int longTrades,
        int shortTrades
) {

    public static WtxRsiMetrics compute(List<WtxRsiTrade> trades, List<EquityPoint> equityCurve) {
        if (trades.isEmpty()) {
            return new WtxRsiMetrics(0, 0, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }
        int wins = 0, losses = 0, longs = 0, shorts = 0;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (WtxRsiTrade t : trades) {
            total = total.add(t.pnlUsd());
            if (t.pnlUsd().signum() > 0) { wins++; grossProfit = grossProfit.add(t.pnlUsd()); }
            else if (t.pnlUsd().signum() < 0) { losses++; grossLoss = grossLoss.add(t.pnlUsd()); }
            if (t.side() == WtxRsiSignal.Side.LONG) longs++; else shorts++;
        }
        BigDecimal winRate = (trades.isEmpty()) ? BigDecimal.ZERO
                : BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(trades.size()), 4, RoundingMode.HALF_UP);
        BigDecimal avgWin = (wins == 0) ? BigDecimal.ZERO
                : grossProfit.divide(BigDecimal.valueOf(wins), 4, RoundingMode.HALF_UP);
        BigDecimal avgLoss = (losses == 0) ? BigDecimal.ZERO
                : grossLoss.divide(BigDecimal.valueOf(losses), 4, RoundingMode.HALF_UP);
        BigDecimal profitFactor;
        if (grossLoss.signum() < 0) {
            profitFactor = grossProfit.divide(grossLoss.abs(), 4, RoundingMode.HALF_UP);
        } else if (grossProfit.signum() > 0) {
            profitFactor = new BigDecimal("999.0000");
        } else {
            profitFactor = BigDecimal.ZERO;
        }
        BigDecimal expectancy = total.divide(BigDecimal.valueOf(trades.size()), 4, RoundingMode.HALF_UP);

        // Max drawdown
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;
        BigDecimal maxPeak = BigDecimal.ZERO;
        for (EquityPoint p : equityCurve) {
            if (p.equityUsd().compareTo(peak) > 0) peak = p.equityUsd();
            BigDecimal dd = p.equityUsd().subtract(peak);
            if (dd.compareTo(maxDd) < 0) maxDd = dd;
            if (peak.compareTo(maxPeak) > 0) maxPeak = peak;
        }
        BigDecimal maxDdPct = (maxPeak.signum() > 0)
                ? maxDd.divide(maxPeak, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new WtxRsiMetrics(
                trades.size(), wins, losses, winRate, avgWin, avgLoss,
                profitFactor, expectancy, total, maxDd, maxDdPct, longs, shorts
        );
    }
}
