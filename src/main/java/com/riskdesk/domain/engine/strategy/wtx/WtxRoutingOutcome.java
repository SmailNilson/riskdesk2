package com.riskdesk.domain.engine.strategy.wtx;

/**
 * Outcome of routing a WTX signal to IBKR auto-execution.
 *
 * Makes the "Auto-IBKR : ON but no order" case diagnosable: every signal that
 * reaches {@code routeToExecution} carries back exactly which gate it cleared or
 * stopped at, surfaced in the signal history, the WS payload and the UI panel.
 *
 * {@code null} on a signal means routing was never attempted (action was NONE,
 * or the signal was purely informative).
 */
public enum WtxRoutingOutcome {
    /** Broker order was submitted (entry, close or both legs of a reverse). */
    ROUTED,
    /** autoExecutionEnabled is false for this (instrument, timeframe). */
    SKIPPED_AUTO_OFF,
    /** The execution bridge bean is not wired (riskdesk.wtx.enabled / IBKR mode off). */
    SKIPPED_BRIDGE_UNAVAILABLE,
    /** IBKR is disabled in the backend (ibkrProperties.isEnabled() == false). */
    SKIPPED_IBKR_DISABLED,
    /** An execution row already exists for this signal's executionKey. */
    SKIPPED_DUPLICATE,
    /** No reference price available to submit the order. */
    SKIPPED_NO_PRICE,
    /** Resulting position quantity was non-positive. */
    SKIPPED_NO_QTY,
    /** A close/reverse was requested but no open WTX execution row exists for this timeframe. */
    SKIPPED_NO_OPEN_ROW,
    /** The broker rejected the order. */
    FAILED
}
