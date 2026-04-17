package com.riskdesk.domain.engine.strategy.agent.zone;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.LiquidityLevel;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

import java.math.BigDecimal;
import java.util.List;

/**
 * ZONE agent — detects proximity to equal-highs / equal-lows liquidity pools.
 *
 * <p>Interpretation is reversal-biased: price approaching a heavy equal-highs cluster
 * tends to tag it and reverse (stops absorbed). So a nearby high-touch equal-high
 * gives a negative (short) vote, and vice-versa for equal-lows.
 */
public final class LiquidityZoneAgent implements StrategyAgent {

    public static final String ID = "liquidity-zone";
    private static final int BASE_VOTE = 35;
    private static final double PROXIMITY_ATR_MULT = 0.5;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public StrategyLayer layer() {
        return StrategyLayer.ZONE;
    }

    @Override
    public AgentVote evaluate(StrategyInput input) {
        MarketContext ctx = input.context();
        if (ctx.lastPrice() == null || ctx.atr() == null) {
            return AgentVote.abstain(ID, StrategyLayer.ZONE, "Missing price or ATR");
        }
        List<LiquidityLevel> levels = input.zones().nearbyLiquidity();
        if (levels.isEmpty()) {
            return AgentVote.abstain(ID, StrategyLayer.ZONE, "No liquidity levels nearby");
        }

        BigDecimal proximity = ctx.atr().multiply(BigDecimal.valueOf(PROXIMITY_ATR_MULT));
        BigDecimal price = ctx.lastPrice();

        LiquidityLevel nearest = null;
        BigDecimal nearestDist = null;
        for (LiquidityLevel lv : levels) {
            BigDecimal dist = lv.price().subtract(price).abs();
            if (dist.compareTo(proximity) > 0) continue;
            if (nearestDist == null || dist.compareTo(nearestDist) < 0) {
                nearest = lv;
                nearestDist = dist;
            }
        }
        if (nearest == null) {
            return AgentVote.abstain(ID, StrategyLayer.ZONE,
                "No liquidity within " + PROXIMITY_ATR_MULT + " × ATR");
        }

        // touchCount scales confidence; more touches = deeper stop pool
        double confidence = Math.min(0.9, 0.3 + 0.15 * nearest.touchCount());
        int vote = nearest.high() ? -BASE_VOTE : +BASE_VOTE;
        String side = nearest.high() ? "equal-highs" : "equal-lows";
        return AgentVote.of(ID, StrategyLayer.ZONE, vote, confidence,
            List.of(side + " at " + nearest.price() + " (touches=" + nearest.touchCount() + ")"));
    }
}
