package com.riskdesk.domain.orderflow.model;

/**
 * Order flow enrichment data for an Order Block zone (UC-OF-009 / Phase 5a).
 * Scores the OB at formation time (historical delta) and at test time (live absorption/depth).
 */
public record OrderBlockEnrichment(
    /** Delta during the impulse that created the OB. */
    double formationDelta,
    /** Volume during the impulse that created the OB. */
    double formationVolume,
    /** |formationDelta| / formationVolume — directional intensity 0-1. */
    double formationDeltaRatio,
    /** 0-100 score at OB formation time. */
    double obFormationScore,
    /** Absorption score if absorption detected in this zone (nullable). */
    Double absorptionScore,
    /** BULLISH or BEARISH (nullable if no absorption). */
    String absorptionSide,
    /** Ratio of bid/ask depth visible in the zone from Level 2 (nullable). */
    Double depthSupportRatio,
    /** True if absorption detected AND price stable in zone. */
    boolean defended,
    /** 0-100 live score combining all real-time OF signals. */
    double obLiveScore
) {}
