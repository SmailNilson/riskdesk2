package com.riskdesk.domain.model;

/**
 * Execution eligibility of a Mentor review.
 *
 * <ul>
 *   <li>{@link #NOT_EVALUATED} — review has not been scored yet (pending/queued).</li>
 *   <li>{@link #ELIGIBLE} — Gemini validated the setup; trade may be armed.</li>
 *   <li>{@link #INELIGIBLE} — Gemini analysed the setup and REJECTED it on merit
 *       (structure, order flow, regime, etc.).</li>
 *   <li>{@link #MENTOR_UNAVAILABLE} — the structured Gemini reply could not be
 *       consumed (truncated JSON, parse failure, I/O error, circuit breaker
 *       open). Not the same as {@code INELIGIBLE}: the trade was never
 *       actually evaluated. UI must surface this distinctly so the user knows
 *       to re-request, not to treat it as a real rejection.</li>
 * </ul>
 *
 * <p>The {@code ExecutionManagerService} gate accepts only {@link #ELIGIBLE},
 * so {@link #MENTOR_UNAVAILABLE} is still blocked from execution — this is
 * intentional, we never arm on an unknown Mentor verdict.
 */
public enum ExecutionEligibilityStatus {
    NOT_EVALUATED,
    ELIGIBLE,
    INELIGIBLE,
    MENTOR_UNAVAILABLE
}
