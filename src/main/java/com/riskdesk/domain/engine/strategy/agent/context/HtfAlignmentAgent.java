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
 * <p>Vote scaling — asymmetric (confirmation outweighs opposition):
 * <ul>
 *   <li>3 of 3 aligned → ±90 conviction (confidence 0.90)</li>
 *   <li>2 of 3 aligned → ±60 conviction (confidence 0.75)</li>
 *   <li>1 of 3 aligned → ±25 conviction (confidence 0.55)</li>
 *   <li>1 of 3 opposed → ±20 against reference (confidence 0.45)</li>
 *   <li>2 of 3 opposed → ±35 against reference (confidence 0.55)</li>
 *   <li>3 of 3 opposed → ±45 against reference (confidence 0.70) — capped to avoid
 *       fully cancelling the reference SMC bias on counter-trend setups</li>
 *   <li>net 0 (split) → ±20 against reference (lonely-but-not-opposed)</li>
 *   <li>reference bias itself NEUTRAL, or all HTFs NEUTRAL → abstain</li>
 * </ul>
 *
 * <p>The sign tracks the reference bias for confirmations and inverts for oppositions.
 * Asymmetry is intentional: a perfect 3/3 opposition should dampen — not erase — the
 * reference signal, otherwise the system never trades counter-trend SMC setups.
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

        // Asymmetric magnitude: a confirmation (net > 0) is a stronger signal than an
        // opposition (net < 0). Treating them symmetrically caused the +90 contre-vote
        // to fully cancel the reference SMC bias (−70) whenever HTFs disagreed, leaving
        // CONTEXT ≈ 0 in the most common counter-trend setup. Cap opposition magnitude
        // at 45 so it dampens — but does not erase — the reference signal.
        int magnitude;
        int sign;
        double confidence;
        if (net > 0) {
            magnitude = switch (net) {
                case 3 -> 90;
                case 2 -> 60;
                case 1 -> 25;
                default -> 0;
            };
            confidence = switch (net) {
                case 3 -> 0.90;
                case 2 -> 0.75;
                default -> 0.55;
            };
            sign = bias == MacroBias.BULL ? +1 : -1;
        } else if (net < 0) {
            // Opposition: capped magnitude. 3-against = 45 (was 90), 2-against = 35,
            // 1-against = 20.
            magnitude = switch (-net) {
                case 3 -> 45;
                case 2 -> 35;
                case 1 -> 20;
                default -> 0;
            };
            confidence = switch (-net) {
                case 3 -> 0.70;
                case 2 -> 0.55;
                default -> 0.45;
            };
            sign = bias == MacroBias.BULL ? -1 : +1;
        } else {
            // net == 0 — HTFs split evenly relative to reference. Lonely-but-not-opposed.
            sign = bias == MacroBias.BULL ? -1 : +1;
            magnitude = 20;
            confidence = 0.45;
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("Reference bias " + bias + " — HTF alignment " + aligned + "/3");
        evidence.add("H1=" + mtf.h1Bias() + " H4=" + mtf.h4Bias() + " D=" + mtf.dailyBias());
        return AgentVote.of(ID, StrategyLayer.CONTEXT, sign * magnitude, confidence, evidence);
    }
}
