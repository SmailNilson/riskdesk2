package com.riskdesk.domain.engine.strategy.agent.context;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.util.List;

/**
 * CONTEXT agent — reads the detected regime and either reinforces the macro bias
 * (TRENDING) or neutralizes the signal (CHOPPY).
 *
 * <p>The regime does NOT pick a direction on its own — TRENDING without a BULL/BEAR
 * macro bias is a CHOPPY signal in disguise. The vote sign always comes from the
 * macro bias of the context; regime only adjusts magnitude.
 */
public final class RegimeContextAgent implements StrategyAgent {

    public static final String ID = "regime-context";

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
        MarketRegime regime = input.context().regime();
        MacroBias bias = input.context().macroBias();

        if (regime == MarketRegime.UNKNOWN) {
            return AgentVote.abstain(ID, StrategyLayer.CONTEXT, "Regime unknown");
        }

        int sign = switch (bias) {
            case BULL -> +1;
            case BEAR -> -1;
            case NEUTRAL -> 0;
        };

        int magnitude = switch (regime) {
            case TRENDING -> 50;
            case RANGING  -> 30;   // mean-reversion favored, small directional edge
            case CHOPPY   -> 10;
            case UNKNOWN  -> 0;
        };

        int vote = sign * magnitude;
        double confidence = regime == MarketRegime.CHOPPY ? 0.4 : 0.75;

        String evidence = "Regime " + regime + " + MacroBias " + bias;
        return AgentVote.of(ID, StrategyLayer.CONTEXT, vote, confidence, List.of(evidence));
    }
}
