package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.engine.playbook.agent.MtfConfluenceAIAgent;
import com.riskdesk.domain.engine.playbook.agent.OrderFlowAIAgent;
import com.riskdesk.domain.engine.playbook.agent.SessionTimingAgent;
import com.riskdesk.domain.engine.playbook.agent.ZoneQualityAIAgent;
import com.riskdesk.domain.engine.playbook.agent.port.GeminiAgentPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the {@code TradingAgent} domain beans so the orchestrator can inject
 * them as a {@code List<TradingAgent>}. Keeps the domain layer Spring-free
 * (ArchUnit enforces this) while still giving us open-for-extension DI:
 * adding a new agent is now a single {@code @Bean} method here, no code
 * changes in {@code AgentOrchestratorService}.
 */
@Configuration
public class TradingAgentConfig {

    @Bean
    public SessionTimingAgent sessionTimingAgent() {
        return new SessionTimingAgent();
    }

    @Bean
    public MtfConfluenceAIAgent mtfConfluenceAIAgent(GeminiAgentPort geminiAgentPort) {
        return new MtfConfluenceAIAgent(geminiAgentPort);
    }

    @Bean
    public OrderFlowAIAgent orderFlowAIAgent(GeminiAgentPort geminiAgentPort) {
        return new OrderFlowAIAgent(geminiAgentPort);
    }

    @Bean
    public ZoneQualityAIAgent zoneQualityAIAgent(GeminiAgentPort geminiAgentPort) {
        return new ZoneQualityAIAgent(geminiAgentPort);
    }
}
