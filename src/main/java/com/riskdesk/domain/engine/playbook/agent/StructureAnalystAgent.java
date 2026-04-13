package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;

import java.util.Map;

/**
 * Agent 1: Evaluates the quality of the market structure mechanically.
 * No AI call -- pure rule-based analysis of breaks, OBs, and structure integrity.
 */
public class StructureAnalystAgent implements TradingAgent {

    @Override
    public String name() { return "StructureAnalyst"; }

    @Override
    public AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null) {
            return AgentVerdict.skip(name(), "No setup to analyze");
        }

        var filters = playbook.filters();
        int total = filters.totalBreaks();
        int valid = filters.validBreaks();
        int fake = filters.fakeBreaks();

        // Check swing-level CHoCH presence (strongest confirmation)
        boolean hasSwingChoch = false;
        PlaybookInput input = context.input();
        if (input != null && input.recentBreaks() != null) {
            hasSwingChoch = input.recentBreaks().stream()
                .anyMatch(b -> "CHOCH".equalsIgnoreCase(b.type())
                            && "SWING".equalsIgnoreCase(b.structureLevel()));
        }

        // Count active OBs aligned with direction
        long alignedObs = 0;
        if (input != null && input.activeOrderBlocks() != null) {
            String dirType = filters.tradeDirection().name().equals("LONG") ? "BULLISH" : "BEARISH";
            alignedObs = input.activeOrderBlocks().stream()
                .filter(ob -> dirType.equalsIgnoreCase(ob.type()))
                .count();
        }

        // Determine confidence
        Confidence confidence;
        String reasoning;

        double validRatio = total > 0 ? (double) valid / total : 1.0;

        if (validRatio >= 0.7 && hasSwingChoch && alignedObs >= 1) {
            confidence = Confidence.HIGH;
            reasoning = String.format("Structure clean: %d/%d valid, swing CHoCH confirmed, %d aligned OBs",
                valid, total, alignedObs);
        } else if (validRatio >= 0.5 || hasSwingChoch) {
            confidence = Confidence.MEDIUM;
            reasoning = String.format("Structure fragile: %d/%d valid (%d FAKE?), %s swing CHoCH",
                valid, total, fake, hasSwingChoch ? "with" : "no");
        } else {
            confidence = Confidence.LOW;
            reasoning = String.format("Structure weak: %d/%d valid (%d FAKE?), no swing confirmation",
                valid, total, fake);
        }

        return new AgentVerdict(name(), confidence, filters.tradeDirection(), reasoning,
            Map.of("valid_ratio", validRatio, "has_swing_choch", hasSwingChoch));
    }
}
