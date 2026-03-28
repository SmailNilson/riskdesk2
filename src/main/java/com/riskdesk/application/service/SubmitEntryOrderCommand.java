package com.riskdesk.application.service;

import java.time.Instant;

public record SubmitEntryOrderCommand(
    Long executionId,
    Instant requestedAt,
    String requestedBy
) {
}
