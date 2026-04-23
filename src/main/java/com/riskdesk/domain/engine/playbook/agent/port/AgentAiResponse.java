package com.riskdesk.domain.engine.playbook.agent.port;

import java.util.Map;

/**
 * Structured response from a Gemini agent call.
 *
 * <p>The adapter enforces Gemini's JSON response schema ({@code confidence} enum,
 * {@code reasoning} string, {@code flags} object). If parsing fails or the API call
 * errors out, the adapter returns {@link #fallback(String)} with {@code aiAvailable=false}
 * so agents can degrade to neutral verdicts without throwing.
 *
 * @param confidence    "HIGH", "MEDIUM", or "LOW" — never null
 * @param reasoning     short analyst-style explanation (&lt; 300 chars)
 * @param flags         additional structured signals the agent emitted
 *                      (e.g. {@code counter_trend=true}, {@code size_pct=0.005})
 * @param aiAvailable   false when the adapter fell back (timeout, disabled, schema mismatch)
 */
public record AgentAiResponse(
    String confidence,
    String reasoning,
    Map<String, Object> flags,
    boolean aiAvailable
) {
    public static final String HIGH = "HIGH";
    public static final String MEDIUM = "MEDIUM";
    public static final String LOW = "LOW";

    public AgentAiResponse {
        if (confidence == null) confidence = MEDIUM;
        if (reasoning == null) reasoning = "";
        if (flags == null) flags = Map.of();
    }

    /** Fallback response when the AI call is unavailable. Callers should treat as neutral. */
    public static AgentAiResponse fallback(String reason) {
        return new AgentAiResponse(MEDIUM, reason, Map.of("fallback", true), false);
    }
}
