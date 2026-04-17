package com.riskdesk.domain.engine.strategy.model;

import java.math.BigDecimal;

/**
 * Layer 3 — the "When".
 *
 * <p>Fast-decaying order-flow facts. This record is rebuilt on every strategy
 * evaluation — it must never be cached across ticks.
 *
 * <p>{@link TickDataQuality} tells downstream agents how much to trust the flow
 * numbers. Under CLV_ESTIMATED the delta comes from a close-location-value proxy on
 * minute candles, not real tick-by-tick; agents should halve their confidence.
 */
public record TriggerContext(
    DeltaSignature deltaSignature,
    /** Fraction of volume classified as buys. Lee-Ready when source is REAL_TICKS. */
    BigDecimal buyRatio,
    BigDecimal cumulativeDelta,
    DomSignal domSignal,
    ReactionPattern reaction,
    TickDataQuality quality
) {
    public TriggerContext {
        if (deltaSignature == null) deltaSignature = DeltaSignature.NEUTRAL;
        if (domSignal == null) domSignal = DomSignal.UNAVAILABLE;
        if (reaction == null) reaction = ReactionPattern.NONE;
        if (quality == null) quality = TickDataQuality.UNAVAILABLE;
    }

    public static TriggerContext unavailable() {
        return new TriggerContext(
            DeltaSignature.NEUTRAL,
            null,
            null,
            DomSignal.UNAVAILABLE,
            ReactionPattern.NONE,
            TickDataQuality.UNAVAILABLE
        );
    }

    /** Confidence multiplier a trigger agent applies based on data quality. */
    public double qualityMultiplier() {
        return switch (quality) {
            case REAL_TICKS     -> 1.0;
            case CLV_ESTIMATED  -> 0.5;
            case UNAVAILABLE    -> 0.2;
        };
    }
}
