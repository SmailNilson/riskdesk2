package com.riskdesk.application.service;

import com.riskdesk.domain.model.ExecutionTriggerSource;

import java.time.Instant;

public record CreateExecutionCommand(
    Long mentorSignalReviewId,
    String brokerAccountId,
    Integer quantity,
    ExecutionTriggerSource triggerSource,
    Instant requestedAt,
    String requestedBy
) {
}
