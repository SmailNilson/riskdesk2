package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for risk management limits.
 * Bound to the "riskdesk.risk" prefix in application properties.
 */
@ConfigurationProperties(prefix = "riskdesk.risk")
public class RiskProperties {

    private double maxMarginUsagePct = 80.0;
    private double maxSinglePositionPct = 25.0;

    /**
     * Maximum $-risk the system is allowed to stake on a single new order.
     * Risk is computed from the worst-case stop-loss:
     * {@code riskUsd = |entry - sl| / tickSize * tickValue * quantity}.
     *
     * <p>Default 500 USD — small enough to prevent a user typo from producing
     * a blow-up order, generous enough to accommodate normal MNQ/MGC/MCL setups.
     */
    private double maxRiskPerTradeUsd = 500.0;

    /**
     * Hard ceiling on the number of contracts per single order, regardless of
     * $-risk. Protects against quantity typos (e.g. "10" → "100") even when the
     * tick-value math underestimates the real exposure.
     */
    private int maxQuantityPerOrder = 20;

    public double getMaxMarginUsagePct() {
        return maxMarginUsagePct;
    }

    public void setMaxMarginUsagePct(double maxMarginUsagePct) {
        this.maxMarginUsagePct = maxMarginUsagePct;
    }

    public double getMaxSinglePositionPct() {
        return maxSinglePositionPct;
    }

    public void setMaxSinglePositionPct(double maxSinglePositionPct) {
        this.maxSinglePositionPct = maxSinglePositionPct;
    }

    public double getMaxRiskPerTradeUsd() {
        return maxRiskPerTradeUsd;
    }

    public void setMaxRiskPerTradeUsd(double maxRiskPerTradeUsd) {
        this.maxRiskPerTradeUsd = maxRiskPerTradeUsd;
    }

    public int getMaxQuantityPerOrder() {
        return maxQuantityPerOrder;
    }

    public void setMaxQuantityPerOrder(int maxQuantityPerOrder) {
        this.maxQuantityPerOrder = maxQuantityPerOrder;
    }
}
