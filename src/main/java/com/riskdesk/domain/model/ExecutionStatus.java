package com.riskdesk.domain.model;

public enum ExecutionStatus {
    PENDING_ENTRY_SUBMISSION,
    ENTRY_SUBMITTED,
    ENTRY_PARTIALLY_FILLED,
    ACTIVE,
    VIRTUAL_EXIT_TRIGGERED,
    EXIT_SUBMITTED,
    CLOSED,
    CANCELLED,
    REJECTED,
    FAILED
}
