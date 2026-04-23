package com.riskdesk.domain.orderflow.model;

/**
 * Order flow enrichment data for Equal Levels liquidity pools (UC-OF-009 / Phase 5a).
 * Level 2 data confirms whether actual orders sit at equal highs/lows levels.
 */
public record LiquidityEnrichment(
    /** True if the order book shows visible orders at this level. */
    boolean ordersVisibleAtLevel,
    /** Total size of visible orders at this level. */
    long totalSizeAtLevel,
    /** Size at this level / average level size in the book. */
    double depthRatioAtLevel,
    /** 0-100 confirmation score for this liquidity pool. */
    double liquidityConfirmScore
) {}
