package com.riskdesk.domain.orderflow.model;

import java.time.Instant;

/**
 * Result of a single Flash Crash FSM evaluation step (UC-OF-006).
 */
public record FlashCrashEvaluation(
    CrashPhase previousPhase,
    CrashPhase currentPhase,
    /** How many of the 5 conditions are currently met (0-5). */
    int conditionsMet,
    /** Which specific conditions are met: [velocity, delta5s, acceleration, depthImbalance, volumeSpike]. */
    boolean[] conditions,
    /** 0-100 reversal probability, meaningful only in DECELERATING/REVERSING phases. */
    double reversalScore,
    Instant timestamp
) {
    public boolean phaseChanged() {
        return previousPhase != currentPhase;
    }
}
