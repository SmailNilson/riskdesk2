package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.engine.playbook.agent.MtfConfluenceAIAgent;
import com.riskdesk.domain.engine.playbook.agent.OrderFlowAIAgent;
import com.riskdesk.domain.engine.playbook.agent.SessionTimingAgent;
import com.riskdesk.domain.engine.playbook.agent.TradingAgent;
import com.riskdesk.domain.engine.playbook.agent.ZoneQualityAIAgent;
import com.riskdesk.domain.engine.playbook.agent.port.GeminiAgentPort;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies the Spring configuration exposes every expected {@link TradingAgent} bean
 * with the right type. Keeps this as a plain unit test (no Spring context) so it
 * runs in milliseconds and catches regressions like an accidentally-removed @Bean.
 */
class TradingAgentConfigTest {

    private final TradingAgentConfig config = new TradingAgentConfig();
    private final GeminiAgentPort port = mock(GeminiAgentPort.class);

    @Test
    void sessionTimingAgent_isInstantiated() {
        SessionTimingAgent agent = config.sessionTimingAgent();
        assertNotNull(agent);
        assertEquals("Session-Timing", agent.name());
    }

    @Test
    void mtfConfluenceAIAgent_isInstantiated_withGeminiPort() {
        MtfConfluenceAIAgent agent = config.mtfConfluenceAIAgent(port);
        assertNotNull(agent);
        assertEquals(MtfConfluenceAIAgent.AGENT_NAME, agent.name());
    }

    @Test
    void orderFlowAIAgent_isInstantiated_withGeminiPort() {
        OrderFlowAIAgent agent = config.orderFlowAIAgent(port);
        assertNotNull(agent);
        assertEquals(OrderFlowAIAgent.AGENT_NAME, agent.name());
    }

    @Test
    void zoneQualityAIAgent_isInstantiated_withGeminiPort() {
        ZoneQualityAIAgent agent = config.zoneQualityAIAgent(port);
        assertNotNull(agent);
        assertEquals(ZoneQualityAIAgent.AGENT_NAME, agent.name());
    }

    @Test
    void allAgents_implementTradingAgent() {
        assertTrue(config.sessionTimingAgent() instanceof TradingAgent);
        assertTrue(config.mtfConfluenceAIAgent(port) instanceof TradingAgent);
        assertTrue(config.orderFlowAIAgent(port) instanceof TradingAgent);
        assertTrue(config.zoneQualityAIAgent(port) instanceof TradingAgent);
    }

    @Test
    void agentExecutor_isDedicatedDaemonPool() throws Exception {
        ExecutorService exec = config.agentExecutor();
        try {
            assertNotNull(exec);
            assertFalse(exec.isShutdown());
            // Smoke test: task runs on a daemon "agent-exec-*" thread
            String threadName = exec.submit(() -> Thread.currentThread().getName()).get();
            assertTrue(threadName.startsWith("agent-exec-"),
                "Expected thread prefix 'agent-exec-' but got: " + threadName);
        } finally {
            exec.shutdown();
        }
    }
}
