package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Detected spoofing event (UC-OF-005).
 * A large order (wall) appeared in the book and disappeared before execution,
 * potentially followed by price crossing that level.
 */
public record SpoofingSignal(
    Instrument instrument,
    SpoofSide side,
    /** Price level where the ghost order was placed. */
    double priceLevel,
    /** Size of the ghost order. */
    long wallSize,
    /** How long the order was visible before disappearing (seconds). */
    double durationSeconds,
    /** Whether the price crossed through this level after the order disappeared. */
    boolean priceCrossed,
    /** Composite score: (wallSize/avgSize) * (1/duration) * priceCrossedMultiplier. */
    double spoofScore,
    Instant timestamp
) {
    public enum SpoofSide { BID_SPOOF, ASK_SPOOF }
}
