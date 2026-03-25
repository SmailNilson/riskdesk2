package com.riskdesk.domain.trading.aggregate;

import com.riskdesk.domain.model.Position;
import com.riskdesk.domain.shared.vo.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Transient aggregate representing a trader's portfolio.
 * Not JPA-persisted; assembled from existing Position entities and account data.
 * Provides risk metrics such as total exposure, margin usage, and concentration.
 */
public class Portfolio {

    private final Money accountMargin;
    private final List<Position> openPositions;

    public Portfolio(Money accountMargin, List<Position> openPositions) {
        this.accountMargin = accountMargin;
        this.openPositions = List.copyOf(openPositions);
    }

    /**
     * Sums unrealized P&L across all open positions.
     * Positions with null unrealized P&L are treated as $0.
     */
    public Money totalUnrealizedPnL() {
        return openPositions.stream()
                .map(p -> p.getUnrealizedPnL() != null ? Money.of(p.getUnrealizedPnL()) : Money.ZERO)
                .reduce(Money.ZERO, Money::add);
    }

    /**
     * Calculates total notional exposure as sum of abs(entryPrice * contractMultiplier * quantity).
     */
    public Money totalExposure() {
        return openPositions.stream()
                .map(p -> {
                    BigDecimal notional = p.getEntryPrice()
                            .multiply(p.getInstrument().getContractMultiplier())
                            .multiply(BigDecimal.valueOf(p.getQuantity()));
                    return Money.of(notional.abs());
                })
                .reduce(Money.ZERO, Money::add);
    }

    /**
     * Returns margin usage as a percentage: (totalExposure / accountMargin) * 100.
     * Returns 0 if account margin is zero.
     */
    public BigDecimal marginUsedPercent() {
        if (accountMargin.isZero()) return BigDecimal.ZERO;
        return totalExposure().amount()
                .divide(accountMargin.amount(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * Returns true if margin usage exceeds the given threshold percentage.
     */
    public boolean isOverMarginThreshold(BigDecimal thresholdPct) {
        return marginUsedPercent().compareTo(thresholdPct) > 0;
    }

    /**
     * Returns positions whose individual exposure exceeds the given concentration percentage.
     */
    public List<Position> positionsExceedingConcentration(BigDecimal maxPct) {
        Money totalExp = totalExposure();
        if (totalExp.isZero()) return List.of();
        return openPositions.stream().filter(p -> {
            BigDecimal notional = p.getEntryPrice()
                    .multiply(p.getInstrument().getContractMultiplier())
                    .multiply(BigDecimal.valueOf(p.getQuantity()))
                    .abs();
            BigDecimal pct = notional.divide(totalExp.amount(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            return pct.compareTo(maxPct) > 0;
        }).collect(Collectors.toList());
    }

    public List<Position> openPositions() {
        return openPositions;
    }

    public Money accountMargin() {
        return accountMargin;
    }

    public int openPositionCount() {
        return openPositions.size();
    }
}
