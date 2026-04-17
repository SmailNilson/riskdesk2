package com.riskdesk.domain.engine.strategy.agent.trigger;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.DeltaSignature;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import com.riskdesk.domain.engine.strategy.model.TickDataQuality;
import com.riskdesk.domain.engine.strategy.model.TriggerContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * TRIGGER agent — translates delta signature + buy ratio into a directional vote.
 *
 * <p>ABSORPTION is inherently contrarian: heavy sell delta without price drop =
 * bullish (buyers absorbing). FLOW is aligned: positive delta pushing price up =
 * bullish continuation. EXHAUSTION fades the prior move direction, which requires
 * context we don't have in this minimalist pilot — so exhaustion is reported as a
 * small, low-confidence contrarian nudge and we defer the full interpretation.
 */
public final class DeltaFlowTriggerAgent implements StrategyAgent {

    public static final String ID = "delta-flow-trigger";

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
        TriggerContext trig = input.trigger();
        if (trig.quality() == TickDataQuality.UNAVAILABLE) {
            return AgentVote.abstain(ID, StrategyLayer.TRIGGER, "Tick data unavailable");
        }

        int vote;
        List<String> evidence = new ArrayList<>();

        switch (trig.deltaSignature()) {
            case ABSORPTION -> {
                int sign = sellerAbsorbed(trig) ? +1 : -1;
                vote = sign * 70;
                evidence.add("ABSORPTION — " + (sign > 0 ? "buyers" : "sellers")
                    + " absorbing against the flow");
            }
            case FLOW -> {
                int sign = pressureSign(trig);
                vote = sign * 50;
                evidence.add("FLOW — orderly continuation (buyRatio=" + trig.buyRatio() + ")");
            }
            case EXHAUSTION -> {
                int sign = -pressureSign(trig);
                vote = sign * 30;
                evidence.add("EXHAUSTION — fade the prior push");
            }
            case NEUTRAL -> {
                return AgentVote.of(ID, StrategyLayer.TRIGGER, 0,
                    0.3 * trig.qualityMultiplier(),
                    List.of("Delta neutral — no actionable signature"));
            }
            default -> {
                return AgentVote.abstain(ID, StrategyLayer.TRIGGER, "Unexpected delta signature");
            }
        }

        double confidence = 0.8 * trig.qualityMultiplier();
        evidence.add("quality=" + trig.quality());
        return AgentVote.of(ID, StrategyLayer.TRIGGER, vote, confidence, evidence);
    }

    /** +1 if buyRatio above 55%, -1 below 45%, 0 otherwise. */
    private static int pressureSign(TriggerContext trig) {
        BigDecimal br = trig.buyRatio();
        if (br == null) return 0;
        double r = br.doubleValue();
        if (r > 0.55) return +1;
        if (r < 0.45) return -1;
        return 0;
    }

    /**
     * ABSORPTION means the side of the delta was consumed by liquidity on the
     * opposite side: a strongly negative delta (sellers) with no price drop implies
     * buyers absorbed — bullish. We use cumulativeDelta sign; in a future slice the
     * builder will inject the last-bar price move so we can compare directions.
     */
    private static boolean sellerAbsorbed(TriggerContext trig) {
        BigDecimal delta = trig.cumulativeDelta();
        if (delta == null) return trig.buyRatio() != null && trig.buyRatio().doubleValue() < 0.5;
        return delta.signum() < 0;
    }
}
