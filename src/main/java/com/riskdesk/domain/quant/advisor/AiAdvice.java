package com.riskdesk.domain.quant.advisor;

import java.time.Instant;

/**
 * Advisory verdict returned by the AI advisor. Strictly informational — the
 * trader makes the final call.
 */
public record AiAdvice(
    Verdict verdict,
    String reasoning,
    String risk,
    double confidence,
    String model,
    Instant generatedAt
) {
    public enum Verdict { TRADE, ATTENDRE, EVITER, UNAVAILABLE }

    /** Used when the advisor is disabled, the LLM is down or the response cannot be parsed. */
    public static AiAdvice unavailable(String reason) {
        return new AiAdvice(Verdict.UNAVAILABLE, reason, "", 0.0, "", Instant.now());
    }
}
