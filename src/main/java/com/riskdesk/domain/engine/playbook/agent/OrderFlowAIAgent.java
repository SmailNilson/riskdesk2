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
public class OrderFlowAIAgent implements Scorer {

    public static final String AGENT_NAME = "Order-Flow-AI";
    public static final String PROMPT_KEY = "order-flow";

    private final GeminiAgentPort port;
    private final String systemPrompt;

    public OrderFlowAIAgent(GeminiAgentPort port, String systemPrompt) {
        this.port = port;
        this.systemPrompt = systemPrompt;
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
            AGENT_NAME, systemPrompt, payload, 500));

        if (!ai.aiAvailable()) {
            // Deterministic fallback: flow-based heuristic with momentum veto.
            // PR-5: if momentum contradicts the trade direction (RSI/WT overbought
            // on a LONG, or oversold on a SHORT), the fallback must NOT let the
            // setup slip through on flow-alone alignment — Gemini would have caught
            // it. Pass `momentum` to the fallback so it can apply the veto.
            return fallbackVerdict(direction, flow, absorption, momentum);
        }

        Confidence conf = parseConfidence(ai.confidence());
        return new AgentVerdict(name(), conf, direction, ai.reasoning(),
            AgentAdjustments.fromGeminiFlags(ai.flags()));
    }

    private AgentVerdict fallbackVerdict(Direction direction,
                                          AgentContext.OrderFlowSnapshot flow,
                                          AgentContext.AbsorptionSnapshot absorption,
                                          AgentContext.MomentumSnapshot momentum) {
        boolean isLong = direction == Direction.LONG;
        boolean flowAligned = flow != null
            && ((isLong && flow.isBullishPressure()) || (!isLong && flow.isBearishPressure()));
        boolean absorbedWithUs = absorption != null && absorption.detected()
            && ((isLong && absorption.isBullish()) || (!isLong && absorption.isBearish()));

        // PR-5: momentum veto. If RSI/WT is OVERBOUGHT on a LONG (or OVERSOLD on a SHORT),
        // the signal contradicts and the fallback must downgrade to LOW, even if flow aligns.
        // This closes audit finding S2: Gemini-online catches this via the momentum block in
        // the payload, but the fallback previously ignored it.
        boolean momentumContradicts = momentum != null
            && momentum.momentumContradicts(direction.name());

        Confidence conf;
        String reasoning;
        if (momentumContradicts) {
            conf = Confidence.LOW;
            reasoning = "AI unavailable — fallback: momentum contradicts direction (RSI/WT extreme)";
        } else if (flowAligned && absorbedWithUs) {
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
            AgentAdjustments.flags(Map.of(
                "fallback", true,
                "momentum_veto", momentumContradicts,
                "data_quality", flow != null ? flow.source().toLowerCase() : "none")));
    }

    private static Confidence parseConfidence(String s) {
        if ("HIGH".equalsIgnoreCase(s)) return Confidence.HIGH;
        if ("LOW".equalsIgnoreCase(s)) return Confidence.LOW;
        return Confidence.MEDIUM;
    }
}
