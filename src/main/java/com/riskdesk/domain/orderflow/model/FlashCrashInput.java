package com.riskdesk.domain.orderflow.model;

import java.time.Instant;

/**
 * Input snapshot for the Flash Crash FSM evaluation (UC-OF-006).
 * Assembled from tick data, depth metrics, and volume statistics.
 */
public record FlashCrashInput(
    /** Price movement in ticks per second. */
    double priceVelocity,
    /** Net delta over the last 5 seconds. */
    double delta5s,
    /** Current velocity / previous velocity. > 1.2 = accelerating, < 0.8 = decelerating. */
    double accelerationRatio,
    /** Depth imbalance from order book. < 0.3 = bids fleeing. */
    double depthImbalance,
    /** Current volume / rolling average volume. > 4.0 = spike. */
    double volumeSpikeRatio,
    Instant timestamp
) {}
