package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Detected absorption event (UC-OF-004).
 * High delta in one direction + stable price + high volume = institutional limit orders absorbing aggressive flow.
 */
public record AbsorptionSignal(
    Instrument instrument,
    AbsorptionSide side,
    /** Composite score: (|delta|/threshold) * (1 - priceMove/ATR) * (volume/avgVolume). */
    double absorptionScore,
    /** Net aggressive delta during the detection window. */
    long aggressiveDelta,
    /** Price movement in ticks during the detection window. */
    double priceMoveTicks,
    /** Total volume during the detection window. */
    long totalVolume,
    Instant timestamp
) {
    public enum AbsorptionSide {
        /** Sells absorbed by passive buyers — bullish signal. */
        BULLISH_ABSORPTION,
        /** Buys absorbed by passive sellers — bearish signal. */
        BEARISH_ABSORPTION
    }
}
