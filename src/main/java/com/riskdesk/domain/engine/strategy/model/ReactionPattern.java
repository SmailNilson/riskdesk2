package com.riskdesk.domain.engine.strategy.model;

/**
 * Pattern of recent price reaction at a zone.
 *
 * <ul>
 *   <li>{@link #REJECTION} — long wick against the zone with close in the opposite half.</li>
 *   <li>{@link #ACCEPTANCE} — prolonged trading inside the zone, absorbing without rejecting.</li>
 *   <li>{@link #INDECISION} — doji / narrow-range candle.</li>
 *   <li>{@link #NONE} — no meaningful reaction detected.</li>
 * </ul>
 */
public enum ReactionPattern {
    REJECTION,
    ACCEPTANCE,
    INDECISION,
    NONE
}
