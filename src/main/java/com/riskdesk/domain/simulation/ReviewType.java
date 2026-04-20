package com.riskdesk.domain.simulation;

/**
 * Discriminator for the kind of review a {@link TradeSimulation} is attached to.
 *
 * <p>Auto (signal) reviews live on {@code mentor_signal_reviews}; manual ("Ask Mentor")
 * reviews live on {@code mentor_audits}. Simulation rows reference one or the other
 * via {@code (reviewId, reviewType)}.
 */
public enum ReviewType {
    /** Auto review persisted in {@code mentor_signal_reviews}. */
    SIGNAL,
    /** Manual "Ask Mentor" review persisted in {@code mentor_audits}. */
    AUDIT
}
