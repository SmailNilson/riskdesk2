package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.orderflow.model.CrashPhase;
import com.riskdesk.domain.orderflow.model.FlashCrashEvaluation;
import com.riskdesk.domain.orderflow.model.FlashCrashInput;
import com.riskdesk.domain.orderflow.model.FlashCrashThresholds;

/**
 * Pure state machine for flash crash detection (UC-OF-006).
 * <p>
 * Transitions: NORMAL -> INITIATING -> ACCELERATING -> DECELERATING -> REVERSING -> NORMAL.
 * Each evaluation step inspects 5 conditions from the input snapshot and advances the FSM.
 * <p>
 * This is a stateful service — one instance per instrument. No Spring, no I/O.
 */
public final class FlashCrashFSM {

    private static final int REVERSAL_COOLDOWN_CYCLES = 30;

    private CrashPhase phase = CrashPhase.NORMAL;

    /** Sign of delta5s at the time we entered DECELERATING/REVERSING, used to detect sign flip. */
    private double lastDelta5sSign = 0.0;

    /** Counts consecutive evaluation cycles with conditionsMet == 0 while in REVERSING. */
    private int reversalCooldownCounter = 0;

    /**
     * Evaluate a single FSM step.
     * <p>
     * The 5 conditions are:
     * <ol>
     *   <li>priceVelocity > velocityThreshold</li>
     *   <li>|delta5s| > delta5sThreshold</li>
     *   <li>accelerationRatio > accelerationThreshold</li>
     *   <li>depthImbalance < depthImbalanceThreshold (bids fleeing)</li>
     *   <li>volumeSpikeRatio > volumeSpikeMultiplier</li>
     * </ol>
     *
     * @param input      current market snapshot
     * @param thresholds per-instrument thresholds
     * @return evaluation result with previous/current phase, conditions met, and reversal score
     */
    public FlashCrashEvaluation evaluate(FlashCrashInput input, FlashCrashThresholds thresholds) {
        CrashPhase previousPhase = this.phase;

        // --- Evaluate 5 conditions ---
        boolean[] conditions = new boolean[5];
        conditions[0] = input.priceVelocity() > thresholds.velocityThreshold();
        conditions[1] = Math.abs(input.delta5s()) > thresholds.delta5sThreshold();
        conditions[2] = input.accelerationRatio() > thresholds.accelerationThreshold();
        conditions[3] = input.depthImbalance() < thresholds.depthImbalanceThreshold();
        conditions[4] = input.volumeSpikeRatio() > thresholds.volumeSpikeMultiplier();

        int conditionsMet = 0;
        for (boolean c : conditions) {
            if (c) conditionsMet++;
        }

        // --- Phase transitions ---
        double reversalScore = 0.0;

        switch (this.phase) {
            case NORMAL -> {
                if (conditionsMet >= thresholds.conditionsRequired()) {
                    this.phase = CrashPhase.INITIATING;
                    this.lastDelta5sSign = Math.signum(input.delta5s());
                }
            }
            case INITIATING -> {
                if (input.accelerationRatio() > thresholds.accelerationThreshold()) {
                    this.phase = CrashPhase.ACCELERATING;
                } else if (input.accelerationRatio() < 0.8) {
                    this.phase = CrashPhase.DECELERATING;
                    this.lastDelta5sSign = Math.signum(input.delta5s());
                } else if (conditionsMet < thresholds.conditionsRequired()) {
                    this.phase = CrashPhase.NORMAL;
                    resetInternal();
                }
            }
            case ACCELERATING -> {
                if (input.accelerationRatio() < 0.8) {
                    this.phase = CrashPhase.DECELERATING;
                    this.lastDelta5sSign = Math.signum(input.delta5s());
                } else if (conditionsMet < thresholds.conditionsRequired()) {
                    this.phase = CrashPhase.NORMAL;
                    resetInternal();
                }
            }
            case DECELERATING -> {
                reversalScore = (1.0 - input.accelerationRatio()) * 50.0;
                reversalScore = Math.max(0.0, Math.min(100.0, reversalScore));

                // Delta5s sign flipped = crash is reversing direction
                double currentSign = Math.signum(input.delta5s());
                if (currentSign != 0.0 && this.lastDelta5sSign != 0.0 && currentSign != this.lastDelta5sSign) {
                    this.phase = CrashPhase.REVERSING;
                    this.reversalCooldownCounter = 0;
                } else if (conditionsMet < thresholds.conditionsRequired()) {
                    this.phase = CrashPhase.NORMAL;
                    resetInternal();
                }
            }
            case REVERSING -> {
                reversalScore = 50.0 + (conditionsMet * 10.0);
                reversalScore = Math.min(100.0, reversalScore);

                if (conditionsMet == 0) {
                    this.reversalCooldownCounter++;
                    if (this.reversalCooldownCounter >= REVERSAL_COOLDOWN_CYCLES) {
                        this.phase = CrashPhase.NORMAL;
                        resetInternal();
                    }
                } else {
                    this.reversalCooldownCounter = 0;
                }
            }
        }

        return new FlashCrashEvaluation(
                previousPhase,
                this.phase,
                conditionsMet,
                conditions,
                reversalScore,
                input.timestamp()
        );
    }

    /** Reset FSM to NORMAL state. */
    public void reset() {
        this.phase = CrashPhase.NORMAL;
        resetInternal();
    }

    /** Current FSM phase. */
    public CrashPhase currentPhase() {
        return this.phase;
    }

    private void resetInternal() {
        this.lastDelta5sSign = 0.0;
        this.reversalCooldownCounter = 0;
    }
}
