package com.riskdesk.domain.trading.service;

import com.riskdesk.domain.model.Position;
import com.riskdesk.domain.trading.aggregate.Portfolio;

import java.math.BigDecimal;
import java.util.List;

/**
 * Domain service encapsulating risk limit rules.
 * Evaluates a Portfolio against configurable thresholds for margin usage
 * and position concentration.
 */
public class RiskSpecification {

    private final BigDecimal maxMarginUsagePct;
    private final BigDecimal maxSinglePositionPct;

    public RiskSpecification(double maxMarginUsagePct, double maxSinglePositionPct) {
        this.maxMarginUsagePct = BigDecimal.valueOf(maxMarginUsagePct);
        this.maxSinglePositionPct = BigDecimal.valueOf(maxSinglePositionPct);
    }

    /**
     * Returns true if the portfolio's margin usage exceeds the maximum allowed percentage.
     */
    public boolean isMarginExceeded(Portfolio portfolio) {
        return portfolio.marginUsedPercent().compareTo(maxMarginUsagePct) > 0;
    }

    /**
     * Returns true if the portfolio's margin usage is above the warning threshold
     * (90% of the max) but not yet exceeded.
     */
    public boolean isMarginWarning(Portfolio portfolio) {
        BigDecimal warningThreshold = maxMarginUsagePct.multiply(BigDecimal.valueOf(0.9));
        BigDecimal usage = portfolio.marginUsedPercent();
        return usage.compareTo(warningThreshold) > 0 && !isMarginExceeded(portfolio);
    }

    /**
     * Returns positions whose individual exposure exceeds the maximum single-position
     * concentration percentage.
     */
    public List<Position> concentratedPositions(Portfolio portfolio) {
        return portfolio.positionsExceedingConcentration(maxSinglePositionPct);
    }
}
