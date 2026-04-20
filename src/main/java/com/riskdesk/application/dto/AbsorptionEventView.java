package com.riskdesk.application.dto;

import java.time.Instant;

/**
 * Application-layer view of a persisted absorption detection event (UC-OF-004).
 * Exposed as JSON via {@code GET /api/order-flow/absorption/{instrument}}.
 * Field names are stable API contract — do not rename without a frontend update.
 *
 * @param instrument       futures instrument code
 * @param timestamp        UTC {@link Instant} when absorption was detected
 * @param side             {@code BULLISH_ABSORPTION} or {@code BEARISH_ABSORPTION}
 * @param absorptionScore  detection confidence score (0-100)
 * @param aggressiveDelta  aggressive buy/sell delta absorbed
 * @param priceMoveTicks   price move during the absorption window (ticks)
 * @param totalVolume      total volume traded during the window
 */
public record AbsorptionEventView(
    String instrument,
    Instant timestamp,
    String side,
    double absorptionScore,
    long aggressiveDelta,
    double priceMoveTicks,
    long totalVolume
) {
}
