package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.IndicatorContext;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.util.List;
import java.util.Locale;

/**
 * CONTEXT agent — votes for mean-reversion when price is pinned to a Bollinger
 * band. Uses Bollinger %B (0 = lower band, 1 = upper band).
 *
 * <p>Convention: <b>positive vote = supports LONG</b>, <b>negative = supports SHORT</b>.
 * Near the lower band → bounce risk → favors LONG → positive. Near the upper band
 * → reject risk → favors SHORT → negative. Mid-band (40–60%) abstains: no edge.
 */
public final class BollingerPositionAgent implements StrategyAgent {

    public static final String ID = "bollinger-position";

    private static final double EXTREME_LOW = 0.10;
    private static final double EXTREME_HIGH = 0.90;
    private static final double MID_LOW = 0.40;
    private static final double MID_HIGH = 0.60;
    private static final int EXTREME_VOTE = 50;
    private static final int MODERATE_VOTE = 25;
    private static final double EXTREME_CONFIDENCE = 0.6;
    private static final double MODERATE_CONFIDENCE = 0.3;

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
        IndicatorContext ind = input.context().indicators();
        if (!ind.hasBollinger()) {
            return AgentVote.abstain(ID, StrategyLayer.CONTEXT, "Bollinger %B unavailable");
        }
        double bbPct = ind.bbPct().doubleValue();

        if (bbPct >= MID_LOW && bbPct <= MID_HIGH) {
            return AgentVote.abstain(ID, StrategyLayer.CONTEXT,
                String.format(Locale.ROOT, "Bollinger %%B=%.2f mid-band — no mean-reversion edge", bbPct));
        }

        int directionalVote;
        double confidence;
        String evidence;
        if (bbPct < EXTREME_LOW) {
            directionalVote = EXTREME_VOTE;
            confidence = EXTREME_CONFIDENCE;
            evidence = String.format(Locale.ROOT, "Bollinger %%B=%.2f near lower band — bounce favors LONG", bbPct);
        } else if (bbPct > EXTREME_HIGH) {
            directionalVote = -EXTREME_VOTE;
            confidence = EXTREME_CONFIDENCE;
            evidence = String.format(Locale.ROOT, "Bollinger %%B=%.2f near upper band — reject favors SHORT", bbPct);
        } else if (bbPct < MID_LOW) {
            // (0.10 .. 0.40) — moderate lower half → small LONG bias.
            directionalVote = MODERATE_VOTE;
            confidence = MODERATE_CONFIDENCE;
            evidence = String.format(Locale.ROOT, "Bollinger %%B=%.2f lower half — mild LONG bias", bbPct);
        } else {
            // (0.60 .. 0.90) — moderate upper half → small SHORT bias.
            directionalVote = -MODERATE_VOTE;
            confidence = MODERATE_CONFIDENCE;
            evidence = String.format(Locale.ROOT, "Bollinger %%B=%.2f upper half — mild SHORT bias", bbPct);
        }

        return AgentVote.of(ID, StrategyLayer.CONTEXT, directionalVote, confidence, List.of(evidence));
    }
}
