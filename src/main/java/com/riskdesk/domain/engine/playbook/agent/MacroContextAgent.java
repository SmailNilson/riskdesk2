package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;

import java.util.Map;

/**
 * Agent 3: Evaluates macro context (DXY correlation, session timing, kill zones).
 * Uses available data from RiskDesk — no external API calls.
 */
public class MacroContextAgent implements TradingAgent {

    @Override
    public String name() { return "MacroContext"; }

    @Override
    public AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null) {
            return AgentVerdict.skip(name(), "No setup to contextualize");
        }

        var macro = context.macro();
        var direction = playbook.filters().tradeDirection();
        int positiveFactors = 0;
        int negativeFactors = 0;
        StringBuilder reasoning = new StringBuilder();

        // 1. DXY correlation check
        if (macro.dxyPctChange() != null) {
            boolean dxyUp = macro.dxyPctChange() > 0.1;
            boolean dxyDown = macro.dxyPctChange() < -0.1;

            // For commodities (MCL, MGC): DXY up = bearish
            // For FOREX (E6): DXY up = bearish for EUR
            // For equity index (MNQ): DXY up = mildly bearish
            boolean isDollarSensitiveLong = direction == Direction.LONG;

            if (dxyUp && isDollarSensitiveLong) {
                negativeFactors++;
                reasoning.append(String.format("DXY +%.1f%% headwind. ", macro.dxyPctChange()));
            } else if (dxyDown && isDollarSensitiveLong) {
                positiveFactors++;
                reasoning.append(String.format("DXY %.1f%% tailwind. ", macro.dxyPctChange()));
            } else if (dxyUp && !isDollarSensitiveLong) {
                positiveFactors++;
                reasoning.append(String.format("DXY +%.1f%% supports SHORT. ", macro.dxyPctChange()));
            } else {
                reasoning.append(String.format("DXY %.1f%% neutral. ", macro.dxyPctChange()));
            }
        } else {
            reasoning.append("DXY data unavailable. ");
        }

        // 2. Session timing
        if (macro.sessionPhase() != null) {
            reasoning.append("Session: ").append(macro.sessionPhase()).append(". ");
            if ("ASIAN".equalsIgnoreCase(macro.sessionPhase())) {
                negativeFactors++; // Low liquidity, wider spreads
                reasoning.append("Low liquidity risk. ");
            }
        }

        // 3. Kill zone alignment
        if (macro.isKillZone()) {
            positiveFactors++;
            reasoning.append("Kill zone ACTIVE. ");
        }

        // Determine confidence
        Confidence confidence;
        if (positiveFactors > negativeFactors) {
            confidence = Confidence.HIGH;
        } else if (positiveFactors == negativeFactors) {
            confidence = Confidence.MEDIUM;
        } else {
            confidence = Confidence.LOW;
        }

        Direction bias = negativeFactors > positiveFactors + 1
            ? direction.opposite() : direction;

        return new AgentVerdict(name(), confidence, bias, reasoning.toString().trim(),
            Map.of("positive_factors", positiveFactors, "negative_factors", negativeFactors));
    }
}
