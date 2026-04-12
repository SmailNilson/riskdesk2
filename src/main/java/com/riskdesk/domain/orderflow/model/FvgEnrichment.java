package com.riskdesk.domain.orderflow.model;

/**
 * Order flow enrichment data for a Fair Value Gap (UC-OF-009 / Phase 5a).
 * A FVG formed with strong delta = real institutional imbalance (high quality).
 * A FVG formed with weak delta = low-liquidity gap (low quality).
 */
public record FvgEnrichment(
    /** Net delta during the 3-candle gap formation. */
    double gapDelta,
    /** Total volume during the 3-candle gap formation. */
    double gapVolume,
    /** |gapDelta| / gapVolume — directional intensity 0-1. */
    double imbalanceIntensity,
    /** 0-100 quality score for this FVG. */
    double fvgQualityScore
) {}
