package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;

import java.util.Map;

/**
 * Agent 1 — Multi-Timeframe Confluence Sniper.
 *
 * Detects what the Playbook CAN'T see: whether H1, H4, and Daily structure
 * agree or conflict with the current timeframe setup.
 *
 * A perfect 10m LONG inside a bearish H4 OB is a trap.
 * A 10m LONG with H1+H4+Daily all BULLISH is high conviction.
 */
public class MtfConfluenceAgent implements TradingAgent {

    @Override
    public String name() { return "MTF-Confluence"; }

    @Override
    public AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null) {
            return AgentVerdict.skip(name(), "No setup to analyze");
        }

        var mtf = context.mtf();
        if (mtf == null) {
            return AgentVerdict.skip(name(), "No MTF data available");
        }

        String direction = playbook.filters().tradeDirection().name();
        int alignment = mtf.alignmentScore(direction);

        // Check for conflicting HTF
        boolean h1Conflicts = mtf.h1SwingBias() != null
            && !biasMatches(mtf.h1SwingBias(), direction);
        boolean h4Conflicts = mtf.h4SwingBias() != null
            && !biasMatches(mtf.h4SwingBias(), direction);

        // Check for CHoCH on HTF (trend reversal signal)
        boolean htfChoch = "CHOCH".equalsIgnoreCase(mtf.h1LastBreakType())
                        || "CHOCH".equalsIgnoreCase(mtf.h4LastBreakType());

        Confidence confidence;
        String reasoning;

        if (alignment >= 3) {
            confidence = Confidence.HIGH;
            reasoning = String.format("Triple confluence: H1+H4+Daily all %s — max conviction",
                direction);
        } else if (alignment == 2 && !h4Conflicts) {
            confidence = Confidence.HIGH;
            reasoning = String.format("Double confluence: %d/3 HTF aligned, no H4 conflict",
                alignment);
        } else if (h4Conflicts && htfChoch) {
            confidence = Confidence.LOW;
            reasoning = String.format("DANGER: H4 bias %s opposes %s + HTF CHoCH detected — counter-trend trap",
                mtf.h4SwingBias(), direction);
            return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
                reasoning, Map.of("size_pct", 0.003, "counter_trend", true));
        } else if (h4Conflicts) {
            confidence = Confidence.LOW;
            reasoning = String.format("H4 bias %s opposes %s — reduced conviction",
                mtf.h4SwingBias(), direction);
            return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
                reasoning, Map.of("size_pct", 0.005));
        } else if (h1Conflicts) {
            confidence = Confidence.MEDIUM;
            reasoning = String.format("H1 bias %s opposes %s — watch for reversal",
                mtf.h1SwingBias(), direction);
        } else {
            confidence = Confidence.MEDIUM;
            reasoning = String.format("Partial confluence: %d/3 HTF aligned", alignment);
        }

        return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
            reasoning, Map.of("mtf_alignment", alignment));
    }

    private boolean biasMatches(String bias, String direction) {
        return ("LONG".equalsIgnoreCase(direction) && "BULLISH".equalsIgnoreCase(bias))
            || ("SHORT".equalsIgnoreCase(direction) && "BEARISH".equalsIgnoreCase(bias));
    }
}
