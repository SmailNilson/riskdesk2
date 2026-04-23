package com.riskdesk.application.dto;

import java.time.Instant;

/**
 * Application-layer view of a persisted spoofing detection event (UC-OF-005).
 * Exposed as JSON via {@code GET /api/order-flow/spoofing/{instrument}}.
 * Field names are stable API contract — do not rename without a frontend update.
 *
 * @param instrument       futures instrument code
 * @param timestamp        UTC {@link Instant} when spoofing was detected
 * @param side             {@code BID_SPOOF} or {@code ASK_SPOOF}
 * @param priceLevel       price at which the spoof wall was placed
 * @param wallSize         size of the wall (contracts) before cancellation
 * @param durationSeconds  how long the wall was visible before disappearing
 * @param priceCrossed     true if price crossed the level before the wall vanished
 * @param spoofScore       detection confidence score (0-100)
 */
public record SpoofingEventView(
    String instrument,
    Instant timestamp,
    String side,
    double priceLevel,
    long wallSize,
    double durationSeconds,
    boolean priceCrossed,
    double spoofScore
) {
}
