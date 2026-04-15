package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.Direction;

public record AgentVerdict(
    String agentName,
    Confidence confidence,
    Direction bias,
    String reasoning,
    AgentAdjustments adjustments
) {

    public AgentVerdict {
        if (adjustments == null) adjustments = AgentAdjustments.none();
    }

    public static AgentVerdict skip(String agentName, String reason) {
        return new AgentVerdict(agentName, Confidence.LOW, null, reason, AgentAdjustments.none());
    }

    public static AgentVerdict timeout(String agentName) {
        return new AgentVerdict(agentName, Confidence.LOW, null, "Agent timeout", AgentAdjustments.none());
    }
}
