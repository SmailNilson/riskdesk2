package com.riskdesk.domain.engine.playbook.model;

import com.riskdesk.domain.engine.playbook.agent.AgentVerdict;

import java.util.List;

public record FinalVerdict(
    String verdict,
    PlaybookPlan adjustedPlan,
    double sizePercent,
    List<AgentVerdict> agentVerdicts,
    List<String> warnings,
    String eligibility
) {}
