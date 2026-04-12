package com.riskdesk.domain.orderflow.model;

/**
 * Order flow enrichment data for a BOS/CHoCH structure break (UC-OF-009 / Phase 5a).
 * A break with strong directional delta = confirmed. Weak delta or opposing delta = potential fakeout.
 */
public record BreakEnrichment(
    /** Net delta on the break candle. */
    double breakDelta,
    /** Volume of the break candle. */
    double breakVolume,
    /** Average volume of the 20 candles before the break. */
    double avgVolume,
    /** breakVolume / avgVolume — volume spike ratio. */
    double volumeSpike,
    /** True if volumeSpike > 2.0 AND delta is directional (aligned with break). */
    boolean confirmed,
    /** 0-100 confidence score for this break. */
    double breakConfidenceScore
) {}
