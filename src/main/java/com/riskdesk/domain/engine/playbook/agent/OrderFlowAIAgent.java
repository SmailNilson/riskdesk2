package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.agent.port.AgentAiRequest;
import com.riskdesk.domain.engine.playbook.agent.port.AgentAiResponse;
import com.riskdesk.domain.engine.playbook.agent.port.GeminiAgentPort;
import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Agent 2 — Order Flow Quality (Gemini-powered).
 *
 * Replaces the old rule-based DivergenceHunterAgent. Evaluates whether the
 * REAL tick-by-tick order flow supports or contradicts the trade direction
 * using absorption signals, delta divergence, depth imbalance, and L2 walls.
 *
 * <p>Explicitly treats {@code source=CLV_ESTIMATED} as reduced-confidence
 * (no decisive order-flow signals — only directional hint).
 *
 * <p>Falls back to a neutral MEDIUM verdict if Gemini is unavailable.
 */
public class OrderFlowAIAgent implements TradingAgent {

    public static final String AGENT_NAME = "Order-Flow-AI";

    private static final String SYSTEM_PROMPT = """
        Rôle : Tu es un Analyste Order Flow Institutionnel spécialisé en scalping futures.
        Tu évalues la qualité du flow en temps réel (ticks classifiés Lee-Ready + L2 depth
        + absorption detector) par rapport à la direction du setup proposé.

        ## Hiérarchie de confiance par source
        - source="REAL_TICKS" : absorption, delta divergence, depth imbalance = signaux DÉCISIONNELS.
        - source="CLV_ESTIMATED" : flow = INDICATIF seulement. Ne jamais valider HIGH confidence
          uniquement sur CLV — max MEDIUM. Mentionner "CLV fallback" dans reasoning.

        ## Règles
        1. REAL_TICKS + absorption.side alignée avec direction + absorption.score>=2.0
           + buy_ratio_pct dans le bon sens (>55 pour LONG, <45 pour SHORT) → HIGH.
        2. REAL_TICKS + delta_divergence_detected opposée à la direction
           (ex: price rising + BEARISH_DIVERGENCE sur un LONG) → LOW, flags.size_pct=0.003.
        3. REAL_TICKS + absorption.side opposite à direction → LOW
           (les institutionnels absorbent contre notre trade).
        4. REAL_TICKS + depth.depthImbalance contre la direction (LONG avec imbalance<-0.3,
           ou SHORT avec imbalance>0.3) → reduce to LOW/MEDIUM.
        5. REAL_TICKS + wall présent du côté TP (bidWall pour LONG, askWall pour SHORT) →
           flags.wall_blocking_tp=true (TP peut rebondir).
        6. CLV_ESTIMATED + momentum confirme seulement → MEDIUM, flags.data_quality="degraded".
        7. momentum (rsi/macd/wt) qui contredit clairement (OVERBOUGHT sur LONG, OVERSOLD
           sur SHORT) → abaisse d'un cran la confidence déterminée par le flow.

        ## Sortie JSON OBLIGATOIRE
        {
          "confidence": "HIGH" | "MEDIUM" | "LOW",
          "reasoning": "max 250 caractères, cite source, absorption/delta/depth",
          "flags": {
            "data_quality": "real_ticks" | "degraded",
            "flow_supports": boolean,
            "size_pct": number (optionnel, 0..0.01),
            "wall_blocking_tp": boolean (optionnel)
          }
        }
        """;

    private final GeminiAgentPort port;

    public OrderFlowAIAgent(GeminiAgentPort port) {
        this.port = port;
    }

    @Override
    public String name() { return AGENT_NAME; }

    @Override
    public AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null) {
            return AgentVerdict.skip(name(), "No setup to analyze");
        }

        Direction direction = playbook.filters().tradeDirection();
        var flow = context.orderFlow();
        var depth = context.depth();
        var absorption = context.absorption();
        var momentum = context.momentum();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", context.instrument().name());
        payload.put("timeframe", context.timeframe());
        payload.put("direction", direction.name());
        payload.put("setup_score", playbook.checklistScore());

        // Order flow (tick aggregation)
        Map<String, Object> flowMap = new LinkedHashMap<>();
        if (flow != null) {
            flowMap.put("source", flow.source());
            flowMap.put("buy_volume", flow.buyVolume());
            flowMap.put("sell_volume", flow.sellVolume());
            flowMap.put("delta", flow.delta());
            flowMap.put("cumulative_delta", flow.cumulativeDelta());
            flowMap.put("buy_ratio_pct", flow.buyRatioPct());
            flowMap.put("delta_trend", flow.deltaTrend());
            flowMap.put("divergence_detected", flow.divergenceDetected());
            flowMap.put("divergence_type", flow.divergenceType());
        } else {
            flowMap.put("source", "NONE");
        }
        payload.put("order_flow", flowMap);

        // Depth (L2)
        Map<String, Object> depthMap = new LinkedHashMap<>();
        if (depth != null && depth.available()) {
            depthMap.put("available", true);
            depthMap.put("depth_imbalance", depth.depthImbalance());
            depthMap.put("total_bid_size", depth.totalBidSize());
            depthMap.put("total_ask_size", depth.totalAskSize());
            depthMap.put("spread_ticks", depth.spreadTicks());
            depthMap.put("bid_wall_present", depth.bidWallPresent());
            depthMap.put("ask_wall_present", depth.askWallPresent());
        } else {
            depthMap.put("available", false);
        }
        payload.put("depth", depthMap);

        // Absorption
        Map<String, Object> absMap = new LinkedHashMap<>();
        if (absorption != null && absorption.detected()) {
            absMap.put("detected", true);
            absMap.put("side", absorption.side());
            absMap.put("score", absorption.score());
            absMap.put("price_move_ticks", absorption.priceMoveTicks());
            absMap.put("total_volume", absorption.totalVolume());
        } else {
            absMap.put("detected", false);
        }
        payload.put("absorption", absMap);

        // Momentum (for contradiction check)
        if (momentum != null) {
            Map<String, Object> momMap = new LinkedHashMap<>();
            momMap.put("rsi", momentum.rsi());
            momMap.put("rsi_signal", momentum.rsiSignal());
            momMap.put("macd_histogram", momentum.macdHistogram());
            momMap.put("macd_crossover", momentum.macdCrossover());
            momMap.put("wt_signal", momentum.wtSignal());
            momMap.put("stoch_signal", momentum.stochSignal());
            momMap.put("momentum_contradicts", momentum.momentumContradicts(direction.name()));
            payload.put("momentum", momMap);
        }

        AgentAiResponse ai = port.analyze(new AgentAiRequest(
            AGENT_NAME, SYSTEM_PROMPT, payload, 500));

        if (!ai.aiAvailable()) {
            // Deterministic fallback: flow-based heuristic
            return fallbackVerdict(direction, flow, absorption);
        }

        Confidence conf = parseConfidence(ai.confidence());
        return new AgentVerdict(name(), conf, direction, ai.reasoning(), ai.flags());
    }

    private AgentVerdict fallbackVerdict(Direction direction,
                                          AgentContext.OrderFlowSnapshot flow,
                                          AgentContext.AbsorptionSnapshot absorption) {
        boolean isLong = direction == Direction.LONG;
        boolean flowAligned = flow != null
            && ((isLong && flow.isBullishPressure()) || (!isLong && flow.isBearishPressure()));
        boolean absorbedWithUs = absorption != null && absorption.detected()
            && ((isLong && absorption.isBullish()) || (!isLong && absorption.isBearish()));

        Confidence conf;
        String reasoning;
        if (flowAligned && absorbedWithUs) {
            conf = Confidence.HIGH;
            reasoning = "AI unavailable — fallback: flow + absorption align";
        } else if (flowAligned || absorbedWithUs) {
            conf = Confidence.MEDIUM;
            reasoning = "AI unavailable — fallback: partial flow alignment";
        } else {
            conf = Confidence.LOW;
            reasoning = "AI unavailable — fallback: flow not aligned";
        }
        return new AgentVerdict(name(), conf, direction, reasoning,
            Map.of("fallback", true, "data_quality",
                flow != null ? flow.source().toLowerCase() : "none"));
    }

    private static Confidence parseConfidence(String s) {
        if ("HIGH".equalsIgnoreCase(s)) return Confidence.HIGH;
        if ("LOW".equalsIgnoreCase(s)) return Confidence.LOW;
        return Confidence.MEDIUM;
    }
}
