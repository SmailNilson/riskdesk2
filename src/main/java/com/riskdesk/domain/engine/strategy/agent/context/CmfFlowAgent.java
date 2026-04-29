package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.IndicatorContext;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.util.List;
import java.util.Locale;

/**
 * CONTEXT agent — translates Chaikin Money Flow (CMF) into a directional vote.
 *
 * <p>CMF measures aggregate buying vs selling pressure on roughly [-1, +1].
 * Sustained positive CMF signals accumulation (favors LONG, positive vote);
 * sustained negative signals distribution (favors SHORT, negative vote). Within
 * ±0.05 the reading is too noisy to lean on — abstain.
 */
public final class CmfFlowAgent implements StrategyAgent {

    public static final String ID = "cmf-flow";

    private static final double STRONG_THRESHOLD = 0.15;
    private static final double MILD_THRESHOLD = 0.05;
    private static final int STRONG_VOTE = 70;
    private static final int MILD_VOTE = 30;
    /** Confidence saturates at |CMF| = 0.25 (1.0 / 4.0). */
    private static final double CONFIDENCE_SCALE = 4.0;

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
        if (!ind.hasCmf()) {
            return AgentVote.abstain(ID, StrategyLayer.CONTEXT, "CMF unavailable");
        }
        double cmf = ind.cmf().doubleValue();
        double absCmf = Math.abs(cmf);

        if (absCmf <= MILD_THRESHOLD) {
            return AgentVote.abstain(ID, StrategyLayer.CONTEXT,
                String.format(Locale.ROOT, "CMF=%+.3f within neutral band — no flow edge", cmf));
        }

        int directionalVote;
        String qualifier;
        if (cmf > STRONG_THRESHOLD) {
            directionalVote = STRONG_VOTE;
            qualifier = "strong-buy";
        } else if (cmf > MILD_THRESHOLD) {
            directionalVote = MILD_VOTE;
            qualifier = "mild-buy";
        } else if (cmf < -STRONG_THRESHOLD) {
            directionalVote = -STRONG_VOTE;
            qualifier = "strong-sell";
        } else {
            directionalVote = -MILD_VOTE;
            qualifier = "mild-sell";
        }

        double confidence = Math.min(1.0, absCmf * CONFIDENCE_SCALE);
        String evidence = String.format(Locale.ROOT, "CMF=%+.3f (%s flow)", cmf, qualifier);
        return AgentVote.of(ID, StrategyLayer.CONTEXT, directionalVote, confidence, List.of(evidence));
    }
}
