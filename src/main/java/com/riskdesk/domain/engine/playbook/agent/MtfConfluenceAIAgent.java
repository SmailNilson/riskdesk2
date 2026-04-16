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
 * AI Agent 1 — Multi-Timeframe Confluence (Gemini-powered).
 *
 * Evaluates whether the current-timeframe setup aligns with H1/H4/Daily structure,
 * and whether the last HTF break (BOS/CHoCH) is a confirmed institutional move
 * or a FAKE wick. A 10m LONG inside a BEARISH H4 + fake CHoCH is a trap —
 * the playbook's rule-based filters can't see that.
 *
 * <p>Falls back to a neutral MEDIUM verdict if the Gemini port is unavailable.
 */
public class MtfConfluenceAIAgent implements Scorer {

    public static final String AGENT_NAME = "MTF-Confluence-AI";
    public static final String PROMPT_KEY = "mtf-confluence";

    private final GeminiAgentPort port;
    private final String systemPrompt;

    public MtfConfluenceAIAgent(GeminiAgentPort port, String systemPrompt) {
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

        var mtf = context.mtf();
        if (mtf == null) {
            return AgentVerdict.skip(name(), "No MTF data available");
        }

        Direction direction = playbook.filters().tradeDirection();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instrument", context.instrument().name());
        payload.put("timeframe", context.timeframe());
        payload.put("direction", direction.name());
        payload.put("setup_score", playbook.checklistScore());
        payload.put("setup_zone", playbook.bestSetup().zoneName());

        Map<String, Object> mtfMap = new LinkedHashMap<>();
        mtfMap.put("h1_swing_bias", mtf.h1SwingBias());
        mtfMap.put("h1_internal_bias", mtf.h1InternalBias());
        mtfMap.put("h4_swing_bias", mtf.h4SwingBias());
        mtfMap.put("daily_swing_bias", mtf.dailySwingBias());
        mtfMap.put("h1_last_break_type", mtf.h1LastBreakType());
        mtfMap.put("h4_last_break_type", mtf.h4LastBreakType());
        mtfMap.put("h1_last_break_confidence", mtf.h1LastBreakConfidence());
        mtfMap.put("h1_last_break_confirmed", mtf.h1LastBreakConfirmed());
        mtfMap.put("h4_last_break_confidence", mtf.h4LastBreakConfidence());
        mtfMap.put("h4_last_break_confirmed", mtf.h4LastBreakConfirmed());
        mtfMap.put("alignment_score", mtf.alignmentScore(direction.name()));
        payload.put("mtf", mtfMap);

        AgentAiResponse ai = port.analyze(new AgentAiRequest(
            AGENT_NAME, systemPrompt, payload, 500));

        if (!ai.aiAvailable()) {
            // Deterministic fallback: use alignment score only
            int align = mtf.alignmentScore(direction.name());
            Confidence conf = align >= 2 ? Confidence.HIGH
                            : align == 1 ? Confidence.MEDIUM
                            : Confidence.LOW;
            return new AgentVerdict(name(), conf, direction,
                "AI unavailable — rule fallback: " + align + "/3 HTF aligned",
                AgentAdjustments.flags(Map.of("mtf_alignment", align, "fallback", true)));
        }

        Confidence conf = parseConfidence(ai.confidence());
        return new AgentVerdict(name(), conf, direction, ai.reasoning(),
            AgentAdjustments.fromGeminiFlags(ai.flags()));
    }

    private static Confidence parseConfidence(String s) {
        if ("HIGH".equalsIgnoreCase(s)) return Confidence.HIGH;
        if ("LOW".equalsIgnoreCase(s)) return Confidence.LOW;
        return Confidence.MEDIUM;
    }
}
