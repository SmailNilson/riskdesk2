package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.util.List;

/**
 * CONTEXT agent — reads position relative to the Value Area.
 *
 * <p>Deliberately interprets PriceLocation as a <i>mean-reversion</i> signal within a
 * VA: above VAH biases a return toward POC (vote negative), below VAL biases a
 * return toward POC (vote positive). Trend-following setups get a neutral vote —
 * the regime agent and the playbook selection handle that case.
 */
public final class VolumeProfileContextAgent implements StrategyAgent {

    public static final String ID = "volume-profile-context";
    private static final int VOTE_MAGNITUDE = 40;
    private static final double CONFIDENCE = 0.65;

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
        PriceLocation loc = input.context().priceLocation();
        return switch (loc) {
            case BELOW_VAL -> AgentVote.of(ID, StrategyLayer.CONTEXT, +VOTE_MAGNITUDE, CONFIDENCE,
                List.of("Price below VAL — reversion toward POC more likely"));
            case ABOVE_VAH -> AgentVote.of(ID, StrategyLayer.CONTEXT, -VOTE_MAGNITUDE, CONFIDENCE,
                List.of("Price above VAH — reversion toward POC more likely"));
            case AT_POC -> AgentVote.of(ID, StrategyLayer.CONTEXT, 0, CONFIDENCE * 0.5,
                List.of("Price at POC — no directional edge from volume profile"));
            case INSIDE_VA -> AgentVote.of(ID, StrategyLayer.CONTEXT, 0, CONFIDENCE * 0.3,
                List.of("Price inside Value Area — no extremes to fade"));
            case UNKNOWN -> AgentVote.abstain(ID, StrategyLayer.CONTEXT,
                "Volume profile unavailable (VA width or POC missing)");
        };
    }
}
