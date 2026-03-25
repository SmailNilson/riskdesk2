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
}
