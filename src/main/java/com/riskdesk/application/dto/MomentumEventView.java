package com.riskdesk.application.dto;

import java.time.Instant;

/**
 * Application-layer view of a persisted aggressive momentum burst event.
 * Exposed as JSON via {@code GET /api/order-flow/momentum/{instrument}}.
 */
public record MomentumEventView(
    String instrument,
    Instant timestamp,
    String side,
    double momentumScore,
    long aggressiveDelta,
    double priceMoveTicks,
    double priceMovePoints,
    long totalVolume
) {
}
