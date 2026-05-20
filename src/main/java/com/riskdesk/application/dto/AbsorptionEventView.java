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
 * @param absorptionScore  detection score (CLASSIC: |delta|/threshold × vol/avgVol; DIVERGENCE: × |move|/atr)
 * @param aggressiveDelta  aggressive buy/sell delta absorbed
 * @param priceMoveTicks   price move during the absorption window (ticks, absolute magnitude)
 * @param totalVolume      total volume traded during the window
 * @param absorptionType   {@code CLASSIC} (delta-price agree) or {@code DIVERGENCE} (delta-price oppose). May be null on legacy rows.
 * @param explanation      short plain-English explanation surfaced in the panel. May be empty on legacy rows.
 */
public record AbsorptionEventView(
    String instrument,
    Instant timestamp,
    String side,
    double absorptionScore,
    long aggressiveDelta,
    double priceMoveTicks,
    long totalVolume,
    String absorptionType,
    String explanation
) {
}
