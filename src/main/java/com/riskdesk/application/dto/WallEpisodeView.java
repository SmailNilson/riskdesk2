package com.riskdesk.application.dto;

import java.time.Instant;

/**
 * Application-layer view of a persisted wall episode (UC-OF-012) — the traceability
 * trail of a large resting order, from appearance to outcome.
 * Exposed as JSON via {@code GET /api/order-flow/walls/{instrument}}.
 * Field names are stable API contract — do not rename without a frontend update.
 *
 * @param instrument       futures instrument code
 * @param timestamp        UTC {@link Instant} when the episode was finalized
 * @param side             {@code BID} or {@code ASK}
 * @param price            wall price level
 * @param initialSize      resting size when first flagged
 * @param maxSize          largest resting size observed while flagged
 * @param lastSize         resting size the last time the level was flagged
 * @param firstSeenAt      when the wall was first flagged
 * @param durationSeconds  lifetime as a wall (firstSeenAt → last flagged)
 * @param outcome          {@code CONSUMED}, {@code PULLED}, {@code FADED} or {@code OUT_OF_RANGE}
 * @param endDistanceTicks distance (ticks) to the same-side best at finalization;
 *                         negative = price traded through the level
 */
public record WallEpisodeView(
    String instrument,
    Instant timestamp,
    String side,
    double price,
    long initialSize,
    long maxSize,
    long lastSize,
    Instant firstSeenAt,
    double durationSeconds,
    String outcome,
    double endDistanceTicks
) {
}
