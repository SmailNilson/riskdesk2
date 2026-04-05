package com.riskdesk.domain.forwardtest.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Virtual trading account for forward-testing.
 * Tracks balance, equity curve high-water mark, and aggregate trade stats.
 */
public record ForwardTestAccount(
        Long id,
        String name,
        BigDecimal initialBalance,
        BigDecimal currentBalance,
        BigDecimal peakBalance,
        BigDecimal maxDrawdown,
        BigDecimal maxDrawdownPeakAtEvent,
        int totalTrades,
        int winningTrades,
        int losingTrades,
        Instant createdAt,
        Instant updatedAt
) {

    /** Create a new account with an initial balance. */
    public static ForwardTestAccount create(String name, BigDecimal initialBalance, Instant now) {
        return new ForwardTestAccount(
                null, name, initialBalance, initialBalance, initialBalance,
                BigDecimal.ZERO, initialBalance,
                0, 0, 0, now, now);
    }

    /** Apply a resolved trade P&L (net of commissions) to the account. */
    public ForwardTestAccount applyTradeResult(BigDecimal netPnl, boolean isWin, Instant now) {
        BigDecimal newBalance = currentBalance.add(netPnl);
        BigDecimal newPeak = newBalance.compareTo(peakBalance) > 0 ? newBalance : peakBalance;
        BigDecimal currentDd = newPeak.subtract(newBalance);
        BigDecimal newMaxDd;
        BigDecimal newDdPeak;
        if (currentDd.compareTo(maxDrawdown) > 0) {
            newMaxDd = currentDd;
            newDdPeak = newPeak;
        } else {
            newMaxDd = maxDrawdown;
            newDdPeak = maxDrawdownPeakAtEvent;
        }
        return new ForwardTestAccount(
                id, name, initialBalance, newBalance, newPeak, newMaxDd, newDdPeak,
                totalTrades + 1,
                isWin ? winningTrades + 1 : winningTrades,
                isWin ? losingTrades : losingTrades + 1,
                createdAt, now);
    }

    /** Win rate as percentage (0-100). Returns 0 if no trades. */
    public BigDecimal winRate() {
        if (totalTrades == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(winningTrades)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP);
    }

    /** Current return as percentage of initial balance. */
    public BigDecimal returnPct() {
        if (initialBalance.signum() == 0) return BigDecimal.ZERO;
        return currentBalance.subtract(initialBalance)
                .multiply(BigDecimal.valueOf(100))
                .divide(initialBalance, 2, RoundingMode.HALF_UP);
    }

    /** Max drawdown as percentage of peak at the time the drawdown occurred. */
    public BigDecimal maxDrawdownPct() {
        if (maxDrawdownPeakAtEvent == null || maxDrawdownPeakAtEvent.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return maxDrawdown
                .multiply(BigDecimal.valueOf(100))
                .divide(maxDrawdownPeakAtEvent, 2, RoundingMode.HALF_UP);
    }
}
