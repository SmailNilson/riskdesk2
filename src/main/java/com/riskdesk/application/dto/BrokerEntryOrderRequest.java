package com.riskdesk.application.dto;

import java.math.BigDecimal;

public record BrokerEntryOrderRequest(
    Long executionId,
    String executionKey,
    String brokerAccountId,
    String instrument,
    String action,
    Integer quantity,
    BigDecimal limitPrice
) {
}
