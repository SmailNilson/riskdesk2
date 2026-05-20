package com.riskdesk.domain.quant.positions;

import com.riskdesk.domain.model.ExecutionStatus;

import java.time.Instant;

/**
 * Lifecycle event emitted whenever an active position transitions in a way the
 * Active Positions panel must surface (close-requested, status flip, ...).
 *
 * <p>The infrastructure {@code ActivePositionsWebSocketAdapter} listens to
 * this event and pushes the latest active-positions list to {@code
 * /topic/positions} so the frontend panel updates without polling.</p>
 *
 * <p>Pure domain record — no framework dependencies. Carries only the IDs and
 * the new status; consumers are expected to re-read the current list from the
 * application service rather than deserialising the full snapshot here. This
 * keeps the event small and avoids leaking PnL math into the domain.</p>
 */
public record ActivePositionChangedEvent(
    Long executionId,
    String instrument,
    ExecutionStatus newStatus,
    Kind kind,
    Instant changedAt
) {
    public enum Kind {
        /** Operator clicked Close (or backend transitioned the row to a terminal state). */
        CLOSE_REQUESTED,
        /** Periodic 5s heartbeat — no actual change, just a refresh tick. */
        HEARTBEAT
    }
}
