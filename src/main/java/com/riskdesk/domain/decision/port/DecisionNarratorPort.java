package com.riskdesk.domain.decision.port;

/**
 * Domain boundary for the AI narrator. Implementations turn a {@link NarratorRequest}
 * into a short natural-language paragraph without re-deciding the verdict.
 *
 * <p>The concrete adapter lives in {@code application/service/GeminiNarrator} — it calls
 * Gemini with a very constrained system prompt ("narrate, do not decide"). The port itself
 * contains no Spring or JSON dependencies so the decision domain stays pure.
 *
 * <p>Implementations <b>must not throw</b> on external failures — they should return
 * {@link NarratorResponse#fallback(String)} so the caller (which already has the verdict)
 * can persist the decision with a minimal fallback narrative.
 */
public interface DecisionNarratorPort {

    NarratorResponse narrate(NarratorRequest request);
}
