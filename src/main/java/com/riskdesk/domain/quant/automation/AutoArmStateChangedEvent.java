package com.riskdesk.domain.quant.automation;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Lifecycle event for an auto-armed execution. The infrastructure layer
 * publishes this on {@code /topic/quant/auto-arm/{instrument}} so the
 * frontend can transition the badge UI without polling.
 */
public record AutoArmStateChangedEvent(
    Instrument instrument,
    Long executionId,
    State state,
    String reason,
    Instant changedAt
) {
    public enum State {
        /** Decision created, countdown started. (Issued via {@link AutoArmFiredEvent} as well.) */
        ARMED,
        /** Operator clicked Fire — IBKR submission was kicked off immediately. */
        FIRED,
        /** Operator clicked Cancel — execution moved to {@link com.riskdesk.domain.model.ExecutionStatus#CANCELLED}. */
        CANCELLED,
        /** Decision aged past {@code expireSeconds} without firing. */
        EXPIRED,
        /** Auto-submit scheduler just submitted the entry order. */
        AUTO_SUBMITTED
    }
}
