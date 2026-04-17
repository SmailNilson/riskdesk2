package com.riskdesk.domain.engine.strategy.model;

/**
 * Coarse classification of the most recent order-flow delta signature.
 *
 * <ul>
 *   <li>{@link #ABSORPTION} — large delta opposing price movement (institutional limit
 *       fills consuming retail market orders). Example: strong negative delta with
 *       price refusing to drop = buyers absorbing.</li>
 *   <li>{@link #EXHAUSTION} — delta collapses after an extended directional run; last
 *       push-higher has no volume behind it.</li>
 *   <li>{@link #FLOW} — delta aligned with price direction, orderly continuation.</li>
 *   <li>{@link #NEUTRAL} — no actionable signature (typical inside-range chop).</li>
 * </ul>
 */
public enum DeltaSignature {
    ABSORPTION,
    EXHAUSTION,
    FLOW,
    NEUTRAL
}
