package com.riskdesk.application.dto;

import java.time.Instant;

/**
 * Application-layer view of a persisted iceberg detection event.
 * Exposed as JSON via {@code GET /api/order-flow/iceberg/{instrument}}.
 * Field names are stable API contract — do not rename without a frontend update.
 *
 * @param instrument       futures instrument code (e.g. "MNQ", "MCL")
 * @param timestamp        UTC {@link Instant} when the iceberg was detected
 * @param side             {@code BID_ICEBERG} or {@code ASK_ICEBERG}
 * @param priceLevel       price at which the iceberg was resting
 * @param rechargeCount    number of APPEAR/DISAPPEAR cycles observed
 * @param avgRechargeSize  average wall size (contracts) at each recharge
 * @param durationSeconds  total duration of iceberg activity, in seconds
 * @param icebergScore     detection confidence score (0-100)
 */
public record IcebergEventView(
    String instrument,
    Instant timestamp,
    String side,
    double priceLevel,
    int rechargeCount,
    long avgRechargeSize,
    double durationSeconds,
    double icebergScore
) {
}
