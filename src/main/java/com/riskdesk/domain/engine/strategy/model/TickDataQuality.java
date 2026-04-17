package com.riskdesk.domain.engine.strategy.model;

/**
 * Provenance of the tick data used to build the {@link TriggerContext}.
 * Agents MUST lower their {@code confidence} when source is CLV_ESTIMATED or UNAVAILABLE.
 */
public enum TickDataQuality {
    REAL_TICKS,
    CLV_ESTIMATED,
    UNAVAILABLE
}
