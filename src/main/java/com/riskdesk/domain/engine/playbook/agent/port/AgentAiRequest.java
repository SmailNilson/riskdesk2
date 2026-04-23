package com.riskdesk.domain.engine.playbook.agent.port;

import java.util.Map;

/**
 * Request envelope for a single agent → Gemini call.
 *
 * @param agentName      logical agent name (used for logging and verdict attribution)
 * @param systemPrompt   static role/rules prompt — cacheable across calls
 * @param payload        structured domain data (will be JSON-serialized by the adapter)
 * @param maxOutputTokens generation cap — keep tight (400–800) for cheap agent calls
 */
public record AgentAiRequest(
    String agentName,
    String systemPrompt,
    Map<String, Object> payload,
    int maxOutputTokens
) {

    public AgentAiRequest {
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName is required");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload is required (use Map.of() for empty)");
        }
        if (maxOutputTokens <= 0) {
            maxOutputTokens = 600;
        }
    }
}
