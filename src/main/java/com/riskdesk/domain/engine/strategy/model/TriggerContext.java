package com.riskdesk.domain.engine.strategy.model;

import com.riskdesk.domain.quant.pattern.PatternAnalysis;

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
 *
 * <p>{@link #orderFlowPattern} carries the 4-quadrant
 * {@link com.riskdesk.domain.quant.pattern.OrderFlowPattern} classification
 * computed by {@code TriggerContextBuilder} via {@code OrderFlowPatternDetector}.
 * Sharing the Quant detector here is intentional — the deterministic 4-quadrant
 * classifier has built-in hysteresis (PRICE_STABLE_BAND, recentPrices window,
 * INDETERMINE fallback) that the legacy {@code DeltaFlowTriggerAgent} lacked,
 * making the TRIGGER vote stable across consecutive scans. Null when the
 * builder could not compute it (insufficient price history or missing delta).
 */
public record TriggerContext(
    DeltaSignature deltaSignature,
    /** Fraction of volume classified as buys. Lee-Ready when source is REAL_TICKS. */
    BigDecimal buyRatio,
    BigDecimal cumulativeDelta,
    DomSignal domSignal,
    ReactionPattern reaction,
    TickDataQuality quality,
    /** Stable 4-quadrant pattern from the Quant subdomain — null if not computed. */
    PatternAnalysis orderFlowPattern
) {
    public TriggerContext {
        if (deltaSignature == null) deltaSignature = DeltaSignature.NEUTRAL;
        if (domSignal == null) domSignal = DomSignal.UNAVAILABLE;
        if (reaction == null) reaction = ReactionPattern.NONE;
        if (quality == null) quality = TickDataQuality.UNAVAILABLE;
    }

    /**
     * Back-compat 6-arg constructor — keeps callers and tests that don't yet
     * supply a precomputed {@link PatternAnalysis} compiling. The new agent
     * abstains gracefully when {@link #orderFlowPattern} is null.
     */
    public TriggerContext(DeltaSignature deltaSignature,
                          BigDecimal buyRatio,
                          BigDecimal cumulativeDelta,
                          DomSignal domSignal,
                          ReactionPattern reaction,
                          TickDataQuality quality) {
        this(deltaSignature, buyRatio, cumulativeDelta, domSignal, reaction, quality, null);
    }

    public static TriggerContext unavailable() {
        return new TriggerContext(
            DeltaSignature.NEUTRAL,
            null,
            null,
            DomSignal.UNAVAILABLE,
            ReactionPattern.NONE,
            TickDataQuality.UNAVAILABLE,
            null
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
