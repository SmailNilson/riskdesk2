package com.riskdesk.domain.engine.strategy.agent.trigger;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.util.List;

/**
 * TRIGGER agent — interprets the most recent reaction candle.
 *
 * <p>This is a "confirmation" vote — without a nearby zone, a rejection candle means
 * nothing. We emit a small-magnitude vote whose direction is inferred from
 * {@code cumulativeDelta.signum()} when available; otherwise we abstain.
 */
public final class ReactionTriggerAgent implements StrategyAgent {

    public static final String ID = "reaction-trigger";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyLayer layer() {
        return StrategyLayer.TRIGGER;
    }

    @Override
    public AgentVote evaluate(StrategyInput input) {
        ReactionPattern pattern = input.trigger().reaction();
        return switch (pattern) {
            case REJECTION -> {
                int sign = signFromDelta(input);
                if (sign == 0) {
                    yield AgentVote.abstain(ID, StrategyLayer.TRIGGER,
                        "REJECTION detected but delta sign unknown");
                }
                yield AgentVote.of(ID, StrategyLayer.TRIGGER, sign * 30,
                    0.5 * input.trigger().qualityMultiplier(),
                    List.of("REJECTION pattern, delta-consistent"));
            }
            case ACCEPTANCE -> AgentVote.of(ID, StrategyLayer.TRIGGER, 0,
                0.4, List.of("ACCEPTANCE — price settling, no trigger"));
            case INDECISION -> AgentVote.of(ID, StrategyLayer.TRIGGER, 0,
                0.3, List.of("INDECISION candle — wait"));
            case NONE -> AgentVote.abstain(ID, StrategyLayer.TRIGGER, "No reaction detected");
        };
    }

    private static int signFromDelta(StrategyInput input) {
        if (input.trigger().cumulativeDelta() == null) return 0;
        // For a bullish rejection (long wick below), we expect cumulative delta to
        // turn positive as buyers step in. So positive delta → +1.
        return Integer.signum(input.trigger().cumulativeDelta().signum());
    }
}
