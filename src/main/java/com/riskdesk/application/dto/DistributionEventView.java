package com.riskdesk.application.dto;

import java.time.Instant;

/**
 * Application-layer view of a persisted distribution / accumulation setup.
 * Exposed as JSON via {@code GET /api/order-flow/distribution/{instrument}}.
 */
public record DistributionEventView(
    String instrument,
    Instant timestamp,
    String type,
    int consecutiveCount,
    double avgScore,
    double totalDurationSeconds,
    double priceAtDetection,
    Double resistanceLevel,
    int confidenceScore
) {
}
