package com.riskdesk.domain.trading.service;

import java.math.BigDecimal;

/**
 * Thrown when a proposed order violates the configured position-sizing limits.
 * Domain-level exception — callers in the application layer translate it to an
 * HTTP 422 (or equivalent) without leaking internal rules to the client.
 */
public class PositionSizeExceededException extends RuntimeException {

    private final int quantity;
    private final BigDecimal riskUsd;
    private final BigDecimal maxRiskUsd;
    private final int maxQuantity;

    public PositionSizeExceededException(String message,
                                         int quantity,
                                         BigDecimal riskUsd,
                                         BigDecimal maxRiskUsd,
                                         int maxQuantity) {
        super(message);
        this.quantity = quantity;
        this.riskUsd = riskUsd;
        this.maxRiskUsd = maxRiskUsd;
        this.maxQuantity = maxQuantity;
    }

    public int quantity() { return quantity; }
    public BigDecimal riskUsd() { return riskUsd; }
    public BigDecimal maxRiskUsd() { return maxRiskUsd; }
    public int maxQuantity() { return maxQuantity; }
}
