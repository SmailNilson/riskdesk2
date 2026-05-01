package com.riskdesk.application.quant.setup.gate;

import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupEvaluationContext;
import com.riskdesk.domain.quant.setup.SetupGate;

/**
 * Requires at least one direction (SHORT or LONG) to carry a quant score
 * meeting the minimum threshold.
 *
 * <p>The default threshold is 5 out of 7 gates — enough conviction without
 * requiring a full 7/7 that would be too rare for actionable setups.</p>
 */
public class ScoreGate implements SetupGate {

    public static final int DEFAULT_MIN_SCORE = 5;

    private final int minScore;

    public ScoreGate() {
        this(DEFAULT_MIN_SCORE);
    }

    public ScoreGate(int minScore) {
        this.minScore = minScore;
    }

    @Override
    public GateCheckResult check(SetupEvaluationContext ctx) {
        // Use the structurally-adjusted scores (finalScore / longFinalScore)
        // so a raw 5/7 reduced to 3/7 by structural warnings does NOT pass —
        // setup quality must reflect the structural penalty.
        int shortScore = ctx.snapshot().finalScore();
        int longScore  = ctx.snapshot().longFinalScore();
        if (shortScore >= minScore || longScore >= minScore) {
            return GateCheckResult.pass("SCORE",
                "short=" + shortScore + " long=" + longScore + " min=" + minScore);
        }
        return GateCheckResult.fail("SCORE",
            "short=" + shortScore + " long=" + longScore + " both below min=" + minScore);
    }
}
