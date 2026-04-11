package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Represents a detected iceberg order — a large resting order that recharges
 * after partial fills to hide true size from the visible book.
 *
 * <p>Detection is based on wall events: if a price level repeatedly transitions
 * APPEARED -> DISAPPEARED -> APPEARED within a short window, the level
 * is likely an iceberg that keeps refilling.</p>
 */
public record IcebergSignal(
    Instrument instrument,
    IcebergSide side,
    double priceLevel,
    int rechargeCount,        // how many times the level refilled
    long avgRechargeSize,     // average size after each recharge
    double durationSeconds,   // total duration of iceberg activity
    double icebergScore,      // confidence score 0-100
    Instant timestamp
) {
    public enum IcebergSide { BID_ICEBERG, ASK_ICEBERG }
}
