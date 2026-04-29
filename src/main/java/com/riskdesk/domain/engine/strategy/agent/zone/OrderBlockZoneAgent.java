package com.riskdesk.domain.engine.strategy.agent.zone;

import com.riskdesk.domain.engine.strategy.agent.StrategyAgent;
import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.OrderBlockZone;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;
import com.riskdesk.domain.engine.strategy.model.ZoneContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ZONE agent — scores how strongly price is positioned relative to a nearby
 * Order Block.
 *
 * <p>Contract:
 * <ul>
 *   <li>No nearby OB → abstain.</li>
 *   <li>Price inside a bullish OB or within 0.5 × ATR of its top → positive vote.</li>
 *   <li>Price inside a bearish OB or within 0.5 × ATR of its bottom → negative vote.</li>
 *   <li>quality score (when available) scales confidence, not magnitude.</li>
 * </ul>
 *
 * <p>The 0.5 × ATR proximity matches the upstream {@code ZoneContextBuilder} band
 * (1.0 × ATR pre-filter) — narrower bands here caused the agent to abstain even when
 * a nearby OB had been pre-loaded, leaving the ZONE layer empirically zero.
 */
public final class OrderBlockZoneAgent implements StrategyAgent {

    public static final String ID = "order-block-zone";
    private static final int BASE_VOTE = 60;
    private static final double PROXIMITY_ATR_MULT = BigDecimal.valueOf(0.5).doubleValue();

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
        ZoneContext zones = input.zones();
        BigDecimal price = ctx.lastPrice();
        BigDecimal atr = ctx.atr();

        if (price == null || atr == null) {
            return AgentVote.abstain(ID, StrategyLayer.ZONE, "Missing price or ATR");
        }
        if (zones.activeOrderBlocks().isEmpty()) {
            return AgentVote.abstain(ID, StrategyLayer.ZONE, "No active order blocks nearby");
        }

        BigDecimal proximity = atr.multiply(BigDecimal.valueOf(PROXIMITY_ATR_MULT));

        OrderBlockZone bestBullish = null;
        OrderBlockZone bestBearish = null;
        BigDecimal bestBullishDist = null;
        BigDecimal bestBearishDist = null;

        for (OrderBlockZone ob : zones.activeOrderBlocks()) {
            BigDecimal dist = distanceTo(ob, price);
            if (dist.compareTo(proximity) > 0 && !ob.contains(price)) continue;

            if (ob.bullish()) {
                if (bestBullishDist == null || dist.compareTo(bestBullishDist) < 0) {
                    bestBullish = ob;
                    bestBullishDist = dist;
                }
            } else {
                if (bestBearishDist == null || dist.compareTo(bestBearishDist) < 0) {
                    bestBearish = ob;
                    bestBearishDist = dist;
                }
            }
        }

        if (bestBullish == null && bestBearish == null) {
            return AgentVote.abstain(ID, StrategyLayer.ZONE,
                "No order blocks within " + PROXIMITY_ATR_MULT + " × ATR");
        }

        // If both sides nearby, the closer one wins — and confidence drops because
        // the zone is crowded.
        OrderBlockZone chosen;
        int sign;
        boolean crowded = bestBullish != null && bestBearish != null;
        if (crowded) {
            if (bestBullishDist.compareTo(bestBearishDist) <= 0) {
                chosen = bestBullish;
                sign = +1;
            } else {
                chosen = bestBearish;
                sign = -1;
            }
        } else if (bestBullish != null) {
            chosen = bestBullish;
            sign = +1;
        } else {
            chosen = bestBearish;
            sign = -1;
        }

        double quality = chosen.qualityScore() != null
            ? Math.max(0.0, Math.min(1.0, chosen.qualityScore() / 100.0))
            : 0.5;
        double confidence = crowded ? quality * 0.6 : quality * 0.9 + 0.1;

        List<String> evidence = new ArrayList<>();
        evidence.add("OB " + (chosen.bullish() ? "BULL" : "BEAR")
            + " [" + chosen.bottom() + " – " + chosen.top() + "]");
        if (chosen.qualityScore() != null) {
            evidence.add("quality=" + String.format("%.0f", chosen.qualityScore()));
        }
        if (crowded) {
            evidence.add("both sides nearby — confidence halved");
        }

        int vote = sign * BASE_VOTE;
        return AgentVote.of(ID, StrategyLayer.ZONE, vote, confidence, evidence);
    }

    private static BigDecimal distanceTo(OrderBlockZone ob, BigDecimal price) {
        if (ob.contains(price)) return BigDecimal.ZERO;
        if (price.compareTo(ob.top()) > 0) return price.subtract(ob.top());
        return ob.bottom().subtract(price);
    }
}
