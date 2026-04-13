package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;

public interface TradingAgent {
    String name();
    AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context);
}
