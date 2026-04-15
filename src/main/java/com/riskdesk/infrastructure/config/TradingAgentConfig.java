package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.engine.playbook.agent.MtfConfluenceAIAgent;
import com.riskdesk.domain.engine.playbook.agent.OrderFlowAIAgent;
import com.riskdesk.domain.engine.playbook.agent.SessionTimingAgent;
import com.riskdesk.domain.engine.playbook.agent.ZoneQualityAIAgent;
import com.riskdesk.domain.engine.playbook.agent.port.GeminiAgentPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Dedicated executor for agent orchestration (4 parallel agents) and parallel
     * MTF snapshot loading (3 timeframes). Sized for 4+3 concurrent tasks per
     * orchestration, plus headroom for concurrent instruments.
     *
     * <p>Isolating this pool from {@code ForkJoinPool.commonPool()} means a slow
     * Gemini call can't starve other {@code CompletableFuture} work in the app
     * (scheduled refresh, WebSocket fanout, etc.).
     */
    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "agent-exec-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // 12 threads = 3 concurrent instruments × (4 agents + 3 MTF timeframes) headroom
        return Executors.newFixedThreadPool(12, tf);
    }
}
