package com.riskdesk.application.dto;

import java.util.List;

/**
 * Machine-readable hint that the signal we evaluated was rejected with enough
 * confluence in the opposite direction that operators should consider an
 * inverse trade instead of dismissing the setup entirely.
 *
 * <p>Produced by the analysis service when Gemini's {@code errors} list
 * contains multiple contradictions aligned with the opposite direction — e.g.
 * a LONG signal rejected with "BEARISH_DIVERGENCE", "CHoCH baissier",
 * "buy ratio faible", "DXY haussier". The hint is advisory: the system never
 * auto-executes it, but it surfaces in {@link MentorStructuredResponse} so
 * UIs and follow-up manual reviews can see that value was still on the table.
 *
 * <p>{@code confidenceScore} is a 0-1 ratio of contradictory signals found
 * vs the threshold ({@value com.riskdesk.domain.analysis.InverseBiasAnalyzer#MIN_CONTRADICTIONS}).
 * A score of 1.0 means exactly the threshold was met; 2.0 means double the
 * threshold; 0.0 means no hint (and the record will be {@code null} in that
 * case, not constructed with zeros).
 */
public record MentorInverseBiasHint(
    String direction,          // "LONG" or "SHORT" — the inverse of the rejected signal
    double confidenceScore,    // >= 1.0 means the threshold was cleared
    List<String> supportingErrors,   // The error strings that pointed the other way
    String reasoning           // One-line human-readable summary
) {
    public MentorInverseBiasHint {
        if (direction == null || direction.isBlank()) {
            throw new IllegalArgumentException("direction is required");
        }
        supportingErrors = supportingErrors == null ? List.of() : List.copyOf(supportingErrors);
    }
}
