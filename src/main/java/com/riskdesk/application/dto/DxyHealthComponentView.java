package com.riskdesk.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record DxyHealthComponentView(
    String pair,
    BigDecimal bid,
    BigDecimal ask,
    BigDecimal last,
    BigDecimal effectivePrice,
    String pricingMethod,
    Instant timestamp,
    String status,
    String message
) {
}
