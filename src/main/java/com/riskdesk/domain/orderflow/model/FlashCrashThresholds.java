package com.riskdesk.domain.orderflow.model;

/**
 * Configurable thresholds for the 5 flash crash detection conditions (UC-OF-006).
 * Per-instrument, persisted via FlashCrashConfigPort.
 */
public record FlashCrashThresholds(
    /** Price ticks per second that triggers velocity condition. */
    double velocityThreshold,
    /** Absolute net delta over 5 seconds that triggers delta condition. */
    double delta5sThreshold,
    /** Velocity ratio (current/previous) above which = accelerating. */
    double accelerationThreshold,
    /** Depth imbalance below which = bids fleeing (crash). */
    double depthImbalanceThreshold,
    /** Volume as multiple of average that triggers volume spike condition. */
    double volumeSpikeMultiplier,
    /** Number of conditions required to trigger (default 3). */
    int conditionsRequired
) {
    public static FlashCrashThresholds defaults() {
        return new FlashCrashThresholds(15.0, 300, 1.2, 0.3, 4.0, 3);
    }
}
