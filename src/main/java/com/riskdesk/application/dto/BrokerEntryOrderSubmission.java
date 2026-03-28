package com.riskdesk.application.dto;

import java.time.Instant;

public record BrokerEntryOrderSubmission(
    Long brokerOrderId,
    String brokerOrderStatus,
    String orderRef,
    Instant submittedAt
) {
}
