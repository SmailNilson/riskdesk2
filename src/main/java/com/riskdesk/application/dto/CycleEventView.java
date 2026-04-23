package com.riskdesk.application.dto;

import java.time.Instant;

/**
 * Application-layer view of a persisted smart-money cycle event.
 * Exposed as JSON via {@code GET /api/order-flow/cycle/{instrument}}.
 */
public record CycleEventView(
    String instrument,
    Instant timestamp,
    String cycleType,
    String currentPhase,
    double priceAtPhase1,
    Double priceAtPhase2,
    Double priceAtPhase3,
    double totalPriceMove,
    double totalDurationMinutes,
    int confidence,
    Instant startedAt,
    Instant completedAt
) {
}
