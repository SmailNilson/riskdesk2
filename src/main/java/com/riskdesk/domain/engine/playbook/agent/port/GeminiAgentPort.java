package com.riskdesk.domain.engine.playbook.agent.port;

/**
 * Domain port for invoking a large-language-model to evaluate a trading agent prompt.
 *
 * <p>Implemented by an application-layer adapter that wraps the Gemini REST client.
 * The domain stays pure: no Spring, no Jackson, no HTTP types — only Java primitives
 * and {@code Map<String, Object>} payloads.
 *
 * <p>Each agent calls the port with a scoped {@link AgentAiRequest} describing its role,
 * payload, and expected output shape. The adapter serializes to JSON, sends it to Gemini
 * with {@code responseMimeType=application/json} and a response schema, and parses the
 * result back into an {@link AgentAiResponse}.
 *
 * <p>Failures (timeout, API down, key missing) must NOT throw — the adapter returns
 * {@link AgentAiResponse#fallback(String)} so the orchestrator can degrade gracefully.
 */
public interface GeminiAgentPort {

    AgentAiResponse analyze(AgentAiRequest request);
}
