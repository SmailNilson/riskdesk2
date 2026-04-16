package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.model.Instrument;

import java.util.Map;

/**
 * Agent 4 — Session & Timing Analyst.
 *
 * Detects what the Playbook CAN'T see: whether the timing is right.
 * A 7/7 setup during Asian session with no volume is a trap.
 * A 5/7 setup during NY AM kill zone with high volume is tradeable.
 */
public class SessionTimingAgent implements Gate {

    @Override
    public String name() { return "Session-Timing"; }

    @Override
    public AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null) {
            return AgentVerdict.skip(name(), "No setup to analyze");
        }

        var session = context.session();
        if (session == null) {
            return AgentVerdict.skip(name(), "No session data available");
        }

        Confidence confidence;
        String reasoning;
        AgentAdjustments adjustments;

        if (!session.isMarketOpen()) {
            // Market closed — no trade
            confidence = Confidence.LOW;
            reasoning = "Market CLOSED — cannot execute";
            adjustments = AgentAdjustments.block();
        } else if (session.isMaintenanceWindow()) {
            // Maintenance window — dangerous
            confidence = Confidence.LOW;
            reasoning = "MAINTENANCE WINDOW — spreads wide, fills unreliable";
            adjustments = AgentAdjustments.block();
        } else if (session.isKillZone() && session.isHighLiquidity()) {
            // Kill zone with high liquidity — best timing
            confidence = Confidence.HIGH;
            reasoning = String.format("KILL ZONE (%s) — optimal execution window, high liquidity",
                session.phase());
            adjustments = AgentAdjustments.flags(Map.of("timing_boost", true));
        } else if (session.isHighLiquidity()) {
            // High liquidity session — good
            confidence = Confidence.HIGH;
            reasoning = String.format("%s session — good liquidity for execution", session.phase());
            adjustments = AgentAdjustments.none();
        } else if (session.isLowLiquidity()) {
            // Low liquidity — risky. MCL (Micro WTI Crude) has materially thinner
            // books outside London/NY than the equity-index micros, and the
            // resulting spread widening + stop-hunt risk is not mitigated by a
            // size-cap alone. Block it outright instead.
            confidence = Confidence.LOW;
            if (context.instrument() == Instrument.MCL) {
                reasoning = String.format(
                    "%s session on MCL — crude illiquid outside LONDON/NY, trade blocked",
                    session.phase());
                adjustments = AgentAdjustments.block();
            } else {
                reasoning = String.format("%s session — LOW LIQUIDITY, spreads wider, sweep risk higher",
                    session.phase());
                adjustments = AgentAdjustments.sizeCap(0.005);
            }
        } else {
            // Normal session
            confidence = Confidence.MEDIUM;
            reasoning = String.format("%s session — moderate liquidity", session.phase());
            adjustments = AgentAdjustments.none();
        }

        return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
            reasoning, adjustments);
    }
}
