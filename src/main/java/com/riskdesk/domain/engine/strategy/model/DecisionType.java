package com.riskdesk.domain.engine.strategy.model;

/**
 * Final sizing decision emitted by the StrategyEngine.
 *
 * <p>Ordered from "do nothing" to "full commitment". The policy layer maps a
 * probability score to one of these bucket values; the execution layer (future
 * slice) reads only this field to size orders — it never reads the raw score.
 */
public enum DecisionType {
    /** No playbook applicable, or score below minimum threshold for the candidate setup. */
    NO_TRADE,
    /** A playbook is active but conviction is too low; watch, do not trade. */
    MONITORING,
    /** Setup meets minimum but is below live-trade threshold; log the signal, do not execute. */
    PAPER_TRADE,
    /** Meets live threshold; size at half the risk budget. */
    HALF_SIZE,
    /** Full risk budget. */
    FULL_SIZE;

    public boolean isTradeable() {
        return this == HALF_SIZE || this == FULL_SIZE;
    }
}
