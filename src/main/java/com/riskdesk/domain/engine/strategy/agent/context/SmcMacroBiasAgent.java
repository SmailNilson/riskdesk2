package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.util.List;

/**
 * CONTEXT agent — translates the HTF SMC swing bias into a directional vote.
 *
 * <p>BULL → +70, BEAR → −70, NEUTRAL → abstain (no data / no clarity). We cap at 70
 * rather than 100 because a single swing-bias read is never the whole picture — other
 * context agents (regime, PD zone) add or remove conviction.
 */
public final class SmcMacroBiasAgent implements StrategyAgent {

    public static final String ID = "smc-macro-bias";
    private static final int VOTE_MAGNITUDE = 70;
    private static final double CONFIDENCE = 0.85;

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
        return switch (bias) {
            case BULL -> AgentVote.of(ID, StrategyLayer.CONTEXT, +VOTE_MAGNITUDE, CONFIDENCE,
                List.of("Swing bias BULL on " + input.context().referenceTimeframe()));
            case BEAR -> AgentVote.of(ID, StrategyLayer.CONTEXT, -VOTE_MAGNITUDE, CONFIDENCE,
                List.of("Swing bias BEAR on " + input.context().referenceTimeframe()));
            case NEUTRAL -> AgentVote.abstain(ID, StrategyLayer.CONTEXT,
                "No clear swing bias (NEUTRAL)");
        };
    }
}
