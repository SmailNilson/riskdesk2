package com.riskdesk.infrastructure.prompt;

import com.riskdesk.domain.engine.playbook.agent.MtfConfluenceAIAgent;
import com.riskdesk.domain.engine.playbook.agent.OrderFlowAIAgent;
import com.riskdesk.domain.engine.playbook.agent.ZoneQualityAIAgent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests for {@link AgentPromptRegistry}. Verifies each expected prompt file
 * exists on the classpath, that the registry caches by key, and that unknown
 * keys fail loudly instead of silently returning an empty string.
 */
class AgentPromptRegistryTest {

    private final AgentPromptRegistry registry = new AgentPromptRegistry();

    @Test
    void mtfPromptExists_andMentionsConfluence() {
        String p = registry.prompt(MtfConfluenceAIAgent.PROMPT_KEY);
        assertFalse(p.isBlank());
        assertTrue(p.contains("Multi-Timeframe"),
            "MTF prompt should describe the agent role");
        assertTrue(p.contains("confidence"),
            "MTF prompt should document expected output fields");
    }

    @Test
    void orderFlowPromptExists_andMentionsRealTicks() {
        String p = registry.prompt(OrderFlowAIAgent.PROMPT_KEY);
        assertFalse(p.isBlank());
        assertTrue(p.contains("REAL_TICKS"),
            "Order-flow prompt should mention the tick source hierarchy");
    }

    @Test
    void zoneQualityPromptExists_andMentionsObScore() {
        String p = registry.prompt(ZoneQualityAIAgent.PROMPT_KEY);
        assertFalse(p.isBlank());
        assertTrue(p.contains("ob_live_score"),
            "Zone-quality prompt should reference OB scoring fields");
    }

    @Test
    void sameKey_returnsIdenticalInstance_fromCache() {
        String first = registry.prompt(MtfConfluenceAIAgent.PROMPT_KEY);
        String second = registry.prompt(MtfConfluenceAIAgent.PROMPT_KEY);
        assertSame(first, second, "Second call should return cached reference");
    }

    @Test
    void unknownKey_throwsLoudly() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> registry.prompt("nonexistent-agent"));
        assertTrue(ex.getMessage().contains("nonexistent-agent"));
    }

    @Test
    void promptsDiffer_byAgent() {
        // Sanity: the three prompts should not be identical byte-for-byte.
        // Catches a copy-paste bug where two agents point at the same file.
        String mtf = registry.prompt(MtfConfluenceAIAgent.PROMPT_KEY);
        String flow = registry.prompt(OrderFlowAIAgent.PROMPT_KEY);
        String zone = registry.prompt(ZoneQualityAIAgent.PROMPT_KEY);
        assertFalse(mtf.equals(flow));
        assertFalse(mtf.equals(zone));
        assertFalse(flow.equals(zone));
        // Silence unused assertions
        assertEquals(3, java.util.Set.of(mtf, flow, zone).size());
    }
}
