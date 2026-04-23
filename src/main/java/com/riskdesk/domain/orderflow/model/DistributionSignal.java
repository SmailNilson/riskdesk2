package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Detected institutional distribution or accumulation setup.
 * <p>
 * Fires when N consecutive {@link AbsorptionSignal} events of the same side
 * accumulate with an average score above threshold. A bearish streak at premium
 * indicates distribution (smart money selling into retail buying); a bullish
 * streak at discount indicates accumulation.
 */
public record DistributionSignal(
    Instrument instrument,
    DistributionType type,
    /** Number of consecutive absorption events in the active window. */
    int consecutiveCount,
    /** Mean absorption score across the window. */
    double avgScore,
    /** Time from first to last event in the window, in seconds. */
    double totalDurationSeconds,
    /** Mid-price when the N-th event triggered the signal. */
    double priceAtDetection,
    /** Nullable — nearby resistance/support level if derivable by the orchestrator. */
    Double resistanceLevel,
    /** Detection confidence 0-100. */
    int confidenceScore,
    Instant firstEventTime,
    Instant timestamp
) {
    public enum DistributionType {
        /** Consecutive BEARISH_ABSORPTION — smart money selling. */
        DISTRIBUTION,
        /** Consecutive BULLISH_ABSORPTION — smart money buying. */
        ACCUMULATION
    }
}
