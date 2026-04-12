package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.orderflow.model.CrashPhase;
import com.riskdesk.domain.orderflow.model.FlashCrashEvaluation;
import com.riskdesk.domain.orderflow.model.FlashCrashInput;
import com.riskdesk.domain.orderflow.model.FlashCrashThresholds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class FlashCrashFSMTest {

    private FlashCrashFSM fsm;
    private FlashCrashThresholds thresholds;
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        fsm = new FlashCrashFSM();
        // velocityThreshold=15, delta5sThreshold=300, accelerationThreshold=1.2,
        // depthImbalanceThreshold=0.3, volumeSpikeMultiplier=4.0, conditionsRequired=3
        thresholds = FlashCrashThresholds.defaults();
    }

    // --- Helper: build a FlashCrashInput with all conditions controllable ---
    private FlashCrashInput input(double velocity, double delta5s, double accelRatio,
                                  double depthImbalance, double volumeSpike) {
        return new FlashCrashInput(velocity, delta5s, accelRatio, depthImbalance, volumeSpike, now);
    }

    /** All conditions false: velocity=0, delta=0, accel=0, depthImbalance=0.5 (above 0.3), volumeSpike=0. */
    private FlashCrashInput calmInput() {
        return input(0, 0, 0, 0.5, 0);
    }

    /** 3 conditions met (velocity, delta, volumeSpike) but acceleration below threshold. */
    private FlashCrashInput threeConditionsNoAccel() {
        // velocity=20 > 15, delta=-400 (abs > 300), accel=1.0 (not > 1.2), depth=0.5 (not < 0.3), volume=5 > 4
        return input(20, -400, 1.0, 0.5, 5.0);
    }

    /** 3 conditions met WITH acceleration above threshold. */
    private FlashCrashInput threeConditionsWithAccel() {
        // velocity=20 > 15, delta=-400, accel=1.5 > 1.2, depth=0.5, volume=5 > 4
        return input(20, -400, 1.5, 0.5, 5.0);
    }

    /** All 5 conditions met with high acceleration. */
    private FlashCrashInput allConditionsAccelerating() {
        // velocity=20, delta=-500, accel=2.0, depth=0.1 < 0.3, volume=6.0 > 4
        return input(20, -500, 2.0, 0.1, 6.0);
    }

    // =================== NORMAL phase tests ===================

    @Test
    void normal_staysNormal_whenNoConditionsMet() {
        FlashCrashEvaluation eval = fsm.evaluate(calmInput(), thresholds);

        assertThat(eval.previousPhase()).isEqualTo(CrashPhase.NORMAL);
        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.NORMAL);
        assertThat(eval.conditionsMet()).isEqualTo(0);
        assertThat(eval.phaseChanged()).isFalse();
    }

    @Test
    void normal_staysNormal_whenFewerThanRequiredConditionsMet() {
        // Only 2 conditions: velocity + volumeSpike
        FlashCrashInput twoConditions = input(20, 100, 0.5, 0.5, 5.0);
        FlashCrashEvaluation eval = fsm.evaluate(twoConditions, thresholds);

        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.NORMAL);
        assertThat(eval.conditionsMet()).isEqualTo(2);
    }

    @Test
    void normal_transitionsToInitiating_whenThreeConditionsMet() {
        FlashCrashEvaluation eval = fsm.evaluate(threeConditionsNoAccel(), thresholds);

        assertThat(eval.previousPhase()).isEqualTo(CrashPhase.NORMAL);
        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.INITIATING);
        assertThat(eval.conditionsMet()).isEqualTo(3);
        assertThat(eval.phaseChanged()).isTrue();
    }

    @Test
    void normal_transitionsToInitiating_whenAllFiveConditionsMet() {
        FlashCrashEvaluation eval = fsm.evaluate(allConditionsAccelerating(), thresholds);

        assertThat(eval.previousPhase()).isEqualTo(CrashPhase.NORMAL);
        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.INITIATING);
        assertThat(eval.conditionsMet()).isEqualTo(5);
    }

    @Test
    void normal_reversalScoreIsZero() {
        FlashCrashEvaluation eval = fsm.evaluate(calmInput(), thresholds);
        assertThat(eval.reversalScore()).isEqualTo(0.0);
    }

    // =================== INITIATING phase tests ===================

    @Test
    void initiating_transitionsToAccelerating_whenAccelAboveThreshold() {
        // First go to INITIATING
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.INITIATING);

        // Now feed high acceleration
        FlashCrashEvaluation eval = fsm.evaluate(threeConditionsWithAccel(), thresholds);

        assertThat(eval.previousPhase()).isEqualTo(CrashPhase.INITIATING);
        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.ACCELERATING);
    }

    @Test
    void initiating_transitionsToDecelerating_whenAccelBelow0_8() {
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.INITIATING);

        // acceleration = 0.5 < 0.8 => DECELERATING
        FlashCrashInput decelerating = input(20, -400, 0.5, 0.5, 5.0);
        FlashCrashEvaluation eval = fsm.evaluate(decelerating, thresholds);

        assertThat(eval.previousPhase()).isEqualTo(CrashPhase.INITIATING);
        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.DECELERATING);
    }

    @Test
    void initiating_returnsToNormal_whenConditionsDropBelowRequired() {
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.INITIATING);

        // accel=1.0 (between 0.8 and 1.2) so neither ACCELERATING nor DECELERATING
        // but conditions < 3 => back to NORMAL
        FlashCrashInput fewConditions = input(20, 100, 1.0, 0.5, 1.0);
        FlashCrashEvaluation eval = fsm.evaluate(fewConditions, thresholds);

        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.NORMAL);
    }

    // =================== ACCELERATING phase tests ===================

    @Test
    void accelerating_transitionsToDecelerating_whenAccelDropsBelow0_8() {
        // NORMAL -> INITIATING -> ACCELERATING
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        fsm.evaluate(allConditionsAccelerating(), thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.ACCELERATING);

        FlashCrashInput decel = input(20, -400, 0.6, 0.5, 5.0);
        FlashCrashEvaluation eval = fsm.evaluate(decel, thresholds);

        assertThat(eval.previousPhase()).isEqualTo(CrashPhase.ACCELERATING);
        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.DECELERATING);
    }

    @Test
    void accelerating_staysAccelerating_whenAccelStillHigh() {
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        fsm.evaluate(allConditionsAccelerating(), thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.ACCELERATING);

        FlashCrashEvaluation eval = fsm.evaluate(allConditionsAccelerating(), thresholds);

        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.ACCELERATING);
    }

    @Test
    void accelerating_returnsToNormal_whenConditionsDropBelowRequired() {
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        fsm.evaluate(allConditionsAccelerating(), thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.ACCELERATING);

        // Keep accel above 0.8 but drop conditions below 3
        FlashCrashInput fewConditions = input(5, 100, 1.5, 0.5, 1.0);
        FlashCrashEvaluation eval = fsm.evaluate(fewConditions, thresholds);

        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.NORMAL);
    }

    // =================== DECELERATING phase tests ===================

    @Test
    void decelerating_reversalScoreComputed() {
        // Move to INITIATING then to DECELERATING
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        FlashCrashInput decel = input(20, -400, 0.5, 0.5, 5.0);
        fsm.evaluate(decel, thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.DECELERATING);

        // Now evaluate WHILE in DECELERATING — this is when reversalScore is computed
        FlashCrashInput stillDecel = input(20, -400, 0.5, 0.5, 5.0);
        FlashCrashEvaluation eval = fsm.evaluate(stillDecel, thresholds);

        assertThat(eval.previousPhase()).isEqualTo(CrashPhase.DECELERATING);
        // reversalScore = (1.0 - 0.5) * 50.0 = 25.0
        assertThat(eval.reversalScore()).isCloseTo(25.0, within(0.01));
    }

    @Test
    void decelerating_reversalScoreClampedTo100() {
        // Move to DECELERATING first
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        fsm.evaluate(input(20, -400, 0.5, 0.5, 5.0), thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.DECELERATING);

        // Now evaluate with extreme deceleration
        // accel = -2.0 => (1.0 - (-2.0)) * 50 = 150 => clamped to 100
        FlashCrashInput extremeDecel = input(20, -400, -2.0, 0.5, 5.0);
        FlashCrashEvaluation eval = fsm.evaluate(extremeDecel, thresholds);

        assertThat(eval.reversalScore()).isCloseTo(100.0, within(0.01));
    }

    @Test
    void decelerating_transitionsToReversing_whenDeltaSignFlips() {
        // Enter INITIATING with negative delta
        FlashCrashInput negDelta = input(20, -400, 1.0, 0.5, 5.0);
        fsm.evaluate(negDelta, thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.INITIATING);

        // Enter DECELERATING (accel < 0.8, still negative delta)
        FlashCrashInput decel = input(20, -400, 0.5, 0.5, 5.0);
        fsm.evaluate(decel, thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.DECELERATING);

        // Now delta flips to positive => REVERSING
        FlashCrashInput reversed = input(20, 400, 0.5, 0.5, 5.0);
        FlashCrashEvaluation eval = fsm.evaluate(reversed, thresholds);

        assertThat(eval.previousPhase()).isEqualTo(CrashPhase.DECELERATING);
        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.REVERSING);
    }

    @Test
    void decelerating_returnsToNormal_whenConditionsDropAndNoSignFlip() {
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        FlashCrashInput decel = input(20, -400, 0.5, 0.5, 5.0);
        fsm.evaluate(decel, thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.DECELERATING);

        // Same delta sign, fewer than 3 conditions
        FlashCrashInput fewCond = input(5, -100, 0.5, 0.5, 1.0);
        FlashCrashEvaluation eval = fsm.evaluate(fewCond, thresholds);

        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.NORMAL);
    }

    // =================== REVERSING phase tests ===================

    @Test
    void reversing_reversalScoreReflectsConditionsMet() {
        // Drive to REVERSING
        driveToReversing();

        // Evaluate in REVERSING with 3 conditions met
        FlashCrashInput inReversing = input(20, 400, 0.5, 0.5, 5.0);
        FlashCrashEvaluation eval = fsm.evaluate(inReversing, thresholds);

        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.REVERSING);
        // reversalScore in REVERSING = 50 + (conditionsMet * 10)
        assertThat(eval.reversalScore()).isCloseTo(50.0 + (eval.conditionsMet() * 10.0), within(0.01));
    }

    @Test
    void reversing_returnsToNormal_afterCooldownCyclesWithZeroConditions() {
        driveToReversing();

        // Feed 30 cycles of zero conditions (calm market)
        for (int i = 0; i < 29; i++) {
            FlashCrashEvaluation eval = fsm.evaluate(calmInput(), thresholds);
            assertThat(eval.currentPhase()).isEqualTo(CrashPhase.REVERSING);
        }

        // 30th cycle should trigger return to NORMAL
        FlashCrashEvaluation eval = fsm.evaluate(calmInput(), thresholds);
        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.NORMAL);
    }

    @Test
    void reversing_cooldownResetsWhenConditionsNonZero() {
        driveToReversing();

        // Feed 15 calm cycles (less than 30)
        for (int i = 0; i < 15; i++) {
            fsm.evaluate(calmInput(), thresholds);
        }
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.REVERSING);

        // Feed one cycle with conditions met => cooldown resets
        FlashCrashInput activeInput = input(20, 400, 0.5, 0.5, 5.0);
        fsm.evaluate(activeInput, thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.REVERSING);

        // Now need another full 30 calm cycles to get to NORMAL
        for (int i = 0; i < 29; i++) {
            fsm.evaluate(calmInput(), thresholds);
            assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.REVERSING);
        }
        FlashCrashEvaluation finalEval = fsm.evaluate(calmInput(), thresholds);
        assertThat(finalEval.currentPhase()).isEqualTo(CrashPhase.NORMAL);
    }

    @Test
    void reversing_scoreClampedAt100_withAllConditions() {
        driveToReversing();

        // 5 conditions met: 50 + 5*10 = 100 (exactly at max)
        FlashCrashEvaluation eval = fsm.evaluate(allConditionsAccelerating(), thresholds);
        assertThat(eval.reversalScore()).isCloseTo(100.0, within(0.01));
    }

    // =================== Reset tests ===================

    @Test
    void reset_returnsToNormal() {
        fsm.evaluate(threeConditionsNoAccel(), thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.INITIATING);

        fsm.reset();

        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.NORMAL);
    }

    @Test
    void reset_clearsInternalState_allowsFreshStart() {
        driveToReversing();
        fsm.reset();

        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.NORMAL);

        // Should be able to transition normally from NORMAL again
        FlashCrashEvaluation eval = fsm.evaluate(threeConditionsNoAccel(), thresholds);
        assertThat(eval.previousPhase()).isEqualTo(CrashPhase.NORMAL);
        assertThat(eval.currentPhase()).isEqualTo(CrashPhase.INITIATING);
    }

    // =================== Condition flags test ===================

    @Test
    void conditions_arrayCorrectlyReflectsEachCondition() {
        // velocity=20>15=YES, |delta|=400>300=YES, accel=0.9<1.2=NO, depth=0.5>0.3=NO, volume=5>4=YES
        FlashCrashInput in = input(20, -400, 0.9, 0.5, 5.0);
        FlashCrashEvaluation eval = fsm.evaluate(in, thresholds);

        assertThat(eval.conditions()[0]).isTrue();  // velocity
        assertThat(eval.conditions()[1]).isTrue();  // delta5s
        assertThat(eval.conditions()[2]).isFalse(); // acceleration (0.9 not > 1.2)
        assertThat(eval.conditions()[3]).isFalse(); // depthImbalance (0.5 not < 0.3)
        assertThat(eval.conditions()[4]).isTrue();  // volumeSpike
        assertThat(eval.conditionsMet()).isEqualTo(3);
    }

    // =================== Full lifecycle test ===================

    @Test
    void fullCrashLifecycle_normalToReversingToNormal() {
        // NORMAL -> INITIATING
        FlashCrashEvaluation e1 = fsm.evaluate(threeConditionsNoAccel(), thresholds);
        assertThat(e1.currentPhase()).isEqualTo(CrashPhase.INITIATING);

        // INITIATING -> ACCELERATING
        FlashCrashEvaluation e2 = fsm.evaluate(allConditionsAccelerating(), thresholds);
        assertThat(e2.currentPhase()).isEqualTo(CrashPhase.ACCELERATING);

        // ACCELERATING -> DECELERATING
        FlashCrashInput decel = input(20, -400, 0.6, 0.5, 5.0);
        FlashCrashEvaluation e3 = fsm.evaluate(decel, thresholds);
        assertThat(e3.currentPhase()).isEqualTo(CrashPhase.DECELERATING);

        // DECELERATING -> REVERSING (delta sign flip)
        FlashCrashInput reversed = input(20, 400, 0.6, 0.5, 5.0);
        FlashCrashEvaluation e4 = fsm.evaluate(reversed, thresholds);
        assertThat(e4.currentPhase()).isEqualTo(CrashPhase.REVERSING);

        // REVERSING -> NORMAL (30 calm cycles)
        for (int i = 0; i < 30; i++) {
            fsm.evaluate(calmInput(), thresholds);
        }
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.NORMAL);
    }

    // --- Helper to drive FSM to REVERSING phase ---
    private void driveToReversing() {
        // NORMAL -> INITIATING (negative delta)
        fsm.evaluate(input(20, -400, 1.0, 0.5, 5.0), thresholds);
        // INITIATING -> DECELERATING (accel < 0.8)
        fsm.evaluate(input(20, -400, 0.5, 0.5, 5.0), thresholds);
        // DECELERATING -> REVERSING (delta sign flip)
        fsm.evaluate(input(20, 400, 0.5, 0.5, 5.0), thresholds);
        assertThat(fsm.currentPhase()).isEqualTo(CrashPhase.REVERSING);
    }
}
