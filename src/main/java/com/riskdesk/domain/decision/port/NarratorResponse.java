package com.riskdesk.domain.decision.port;

/**
 * Output of {@link DecisionNarratorPort}. The narrative is a short paragraph intended for
 * UI display (5 lines max). The {@code available} flag lets callers distinguish a real
 * Gemini response from a fallback string when the port is down.
 *
 * @param narrative     the paragraph produced, or a short fallback like {@code "Narrator
 *                      unavailable"} when {@code available=false}
 * @param model         the model identifier when {@code available=true}, else null
 * @param latencyMs     how long the external call took, or 0 for fallback
 * @param available     {@code true} when Gemini produced the text; {@code false} for fallback
 */
public record NarratorResponse(
    String narrative,
    String model,
    long latencyMs,
    boolean available
) {

    public static NarratorResponse fallback(String reason) {
        return new NarratorResponse(reason, null, 0L, false);
    }
}
