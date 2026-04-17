package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MtfSnapshot;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * CONTEXT agent — measures how many higher timeframes (H1 / H4 / Daily) agree
 * with the reference-timeframe {@link MacroBias}.
 *
 * <p>Vote scaling:
 * <ul>
 *   <li>3 of 3 aligned → ±90 conviction with high confidence</li>
 *   <li>2 of 3 aligned → ±60 conviction</li>
 *   <li>1 of 3 aligned → ±25 conviction (soft)</li>
 *   <li>0 of 3 aligned → direction is lonely — vote against it lightly</li>
 *   <li>reference bias itself NEUTRAL, or all HTFs NEUTRAL → abstain</li>
 * </ul>
 *
 * <p>The sign always tracks the reference bias — if H4 and Daily disagree with H1
 * against a reference BULL, we produce a negative vote (bias lonely, HTFs
 * contradicting). Confidence scales with the majority strength.
 */
public final class HtfAlignmentAgent implements StrategyAgent {

    public static final String ID = "htf-alignment";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyLayer layer() {
        return StrategyLayer.CONTEXT;
    }

    @Override
    public AgentVote evaluate(StrategyInput input) {
        MacroBias bias = input.context().macroBias();
        MtfSnapshot mtf = input.context().mtf();

        if (mtf.isAllNeutral()) {
            return AgentVote.abstain(ID, StrategyLayer.CONTEXT,
                "No higher-timeframe data available");
        }
        if (bias == MacroBias.NEUTRAL) {
            // Reference bias unknown, but HTFs tell us something — use the dominant
            // HTF direction to produce a small-magnitude vote.
            int bullCount = mtf.alignmentWith(MacroBias.BULL);
            int bearCount = mtf.alignmentWith(MacroBias.BEAR);
            if (bullCount == bearCount) {
                return AgentVote.abstain(ID, StrategyLayer.CONTEXT,
                    "HTFs split evenly, no alignment signal");
            }
            int sign = bullCount > bearCount ? +1 : -1;
            int magnitude = Math.abs(bullCount - bearCount) * 15; // 15..45
            return AgentVote.of(ID, StrategyLayer.CONTEXT, sign * magnitude, 0.55,
                List.of("Reference bias NEUTRAL; HTFs lean "
                    + (sign > 0 ? "BULL" : "BEAR") + " (" + bullCount + "B/" + bearCount + "S)"));
        }

        int aligned = mtf.alignmentWith(bias);
        int opposed = mtf.alignmentWith(bias == MacroBias.BULL ? MacroBias.BEAR : MacroBias.BULL);
        int net = aligned - opposed; // -3..+3

        int magnitude = switch (Math.abs(net)) {
            case 3 -> 90;
            case 2 -> 60;
            case 1 -> 25;
            default -> 0;
        };
        int sign;
        if (net == 0) {
            // HTFs contradict reference — lonely setup. Vote lightly against the reference.
            sign = bias == MacroBias.BULL ? -1 : +1;
            magnitude = 20;
        } else if (net > 0) {
            sign = bias == MacroBias.BULL ? +1 : -1;
        } else {
            // reference BULL but HTFs mostly BEAR → vote bear-aligned (negative)
            sign = bias == MacroBias.BULL ? -1 : +1;
        }

        double confidence = switch (Math.abs(net)) {
            case 3 -> 0.90;
            case 2 -> 0.75;
            case 1 -> 0.55;
            default -> 0.45;
        };

        List<String> evidence = new ArrayList<>();
        evidence.add("Reference bias " + bias + " — HTF alignment " + aligned + "/3");
        evidence.add("H1=" + mtf.h1Bias() + " H4=" + mtf.h4Bias() + " D=" + mtf.dailyBias());
        return AgentVote.of(ID, StrategyLayer.CONTEXT, sign * magnitude, confidence, evidence);
    }
}
