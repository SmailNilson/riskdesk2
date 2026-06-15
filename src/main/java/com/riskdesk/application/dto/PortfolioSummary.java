package com.riskdesk.application.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record PortfolioSummary(
    BigDecimal totalUnrealizedPnL,
    BigDecimal todayRealizedPnL,
    BigDecimal totalPnL,
    long openPositionCount,
    BigDecimal totalExposure,
    BigDecimal marginUsedPct,
    BigDecimal accountEquity,
    List<PositionView> openPositions
) {

    /**
     * Daily drawdown as a percentage of account equity: today's net P&L
     * (realized + unrealized), counted only when negative, over equity.
     *
     * <p>Equity is the denominator — never notional exposure. IBKR's
     * GrossPositionValue excludes futures, so dividing a dollar P&L by it
     * produced absurd values (e.g. 83.7% on a micro-contract account).
     * Unknown or non-positive equity yields 0 (fail-open: the risk gate
     * abstains rather than blocking on missing broker data).
     */
    public double dailyDrawdownPct() {
        if (accountEquity == null || accountEquity.signum() <= 0) {
            return 0;
        }
        BigDecimal dayPnL = zeroIfNull(totalUnrealizedPnL).add(zeroIfNull(todayRealizedPnL));
        if (dayPnL.signum() >= 0) {
            return 0;
        }
        return dayPnL.abs()
            .divide(accountEquity, 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .doubleValue();
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
