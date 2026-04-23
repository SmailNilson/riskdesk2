package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Detected aggressive momentum burst — the mirror of absorption.
 * <p>
 * Fires when delta, price movement, and volume align in the same direction:
 * aggressive flow is NOT being absorbed, price is moving with the delta.
 * This captures capitulation / trend-continuation phases that occur between
 * the "stable" distribution/accumulation bookends.
 */
public record MomentumSignal(
    Instrument instrument,
    MomentumSide side,
    /** Composite score: (|delta|/threshold) * (priceMove/ATR) * (volume/avgVolume). */
    double momentumScore,
    /** Net aggressive delta during the detection window. */
    long aggressiveDelta,
    /** Absolute price movement in ticks (always positive). */
    double priceMoveTicks,
    /** Signed price move in points (same sign as delta direction). */
    double priceMovePoints,
    /** Total volume during the detection window. */
    long totalVolume,
    Instant timestamp
) {
    public enum MomentumSide {
        /** Aggressive selling pushing price down. */
        BEARISH_MOMENTUM,
        /** Aggressive buying pushing price up. */
        BULLISH_MOMENTUM
    }
}
