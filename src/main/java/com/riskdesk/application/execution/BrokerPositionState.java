package com.riskdesk.application.execution;

import java.math.BigDecimal;

/**
 * Live broker position truth for one instrument, on one account.
 *
 * @param net           signed net position summed across matching contracts ({@code null} when the
 *                      portfolio snapshot is unavailable / not connected).
 * @param confirmedFlat {@code true} only when a connected snapshot shows NO nonzero matching leg.
 *                      Stricter than {@code net == 0}: offsetting legs across expiries (rollover /
 *                      calendar overlap) net to zero but are LIVE positions, so {@code confirmedFlat}
 *                      is {@code false} for them.
 */
public record BrokerPositionState(BigDecimal net, boolean confirmedFlat) {

    public static BrokerPositionState unavailable() {
        return new BrokerPositionState(null, false);
    }

    /** True when the snapshot was readable (net is known). */
    public boolean available() {
        return net != null;
    }

    public boolean isLong() {
        return net != null && net.signum() > 0;
    }

    public boolean isShort() {
        return net != null && net.signum() < 0;
    }

    public boolean isNetZero() {
        return net != null && net.signum() == 0;
    }
}
