package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Detected absorption event (UC-OF-004).
 * High delta in one direction + price action interpreted via the (delta sign × price sign) rule:
 * <ul>
 *   <li>Delta and price OPPOSITE signs → DIVERGENCE — institutional limit orders absorbing aggressive flow.
 *       BULL when delta&lt;0 and price↑ (buyers absorb sellers); BEAR when delta&gt;0 and price↓ (sellers absorb buyers).</li>
 *   <li>Delta and price SAME signs → CLASSIC trend confirmation. BULL when both positive; BEAR when both negative.</li>
 * </ul>
 */
public record AbsorptionSignal(
    Instrument instrument,
    AbsorptionSide side,
    /** Composite score: CLASSIC = (|delta|/threshold) * (vol/avgVol); DIVERGENCE = (|delta|/threshold) * (|priceMove|/atr) * (vol/avgVol). */
    double absorptionScore,
    /** Net aggressive delta during the detection window. */
    long aggressiveDelta,
    /** Price movement in ticks during the detection window (absolute magnitude — kept for back-compat). */
    double priceMoveTicks,
    /** Total volume during the detection window. */
    long totalVolume,
    Instant timestamp,
    /** Whether this is a CLASSIC (delta-price agree) or DIVERGENCE (delta-price oppose) absorption. */
    AbsorptionType absorptionType,
    /** Short plain-English explanation of the signal — surfaced verbatim in the panel. */
    String explanation
) {
    public enum AbsorptionSide {
        /** Sells absorbed by passive buyers — bullish signal. */
        BULLISH_ABSORPTION,
        /** Buys absorbed by passive sellers — bearish signal. */
        BEARISH_ABSORPTION
    }

    public enum AbsorptionType {
        /** Delta and price agree (e.g. delta&gt;0 and price↑). Trend confirmation. */
        CLASSIC,
        /** Delta and price disagree (e.g. delta&lt;0 and price↑). True absorption — strongest signal. */
        DIVERGENCE
    }
}
