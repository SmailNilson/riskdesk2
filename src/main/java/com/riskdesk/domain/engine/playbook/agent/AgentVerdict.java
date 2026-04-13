package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.Direction;

import java.util.Map;

public record AgentVerdict(
    String agentName,
    Confidence confidence,
    Direction bias,
    String reasoning,
    Map<String, Object> adjustments
) {
    public static AgentVerdict skip(String agentName, String reason) {
        return new AgentVerdict(agentName, Confidence.LOW, null, reason, Map.of());
    }

    public static AgentVerdict timeout(String agentName) {
        return new AgentVerdict(agentName, Confidence.LOW, null, "Agent timeout", Map.of());
    }
}
