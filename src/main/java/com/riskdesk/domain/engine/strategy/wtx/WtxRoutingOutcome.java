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
    /**
     * A REVERSE flattened the prior position (close leg sent) but the open leg was
     * skipped because its NET margin delta was unaffordable. The broker ends up
     * <b>FLAT</b> — the user is protected, never stuck in the position the strategy
     * told them to exit. Distinct from {@link #ROUTED} because the caller must NOT
     * leave the virtual strategy state on the new side: it has to be corrected to
     * FLAT to match the broker. Distinct from {@link #SKIPPED_INSUFFICIENT_MARGIN}
     * (a pure OPEN denied with no position to flatten — nothing was sent).
     */
    ROUTED_FLATTEN_ONLY,
    /**
     * The order was sent to IBKR and has a broker order id, but the initial
     * acknowledgement did not arrive before the timeout. Later orderStatus /
     * execDetails callbacks can still reconcile the execution row.
     */
    ACK_PENDING,
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
    /**
     * Pre-flight margin check denied the order: estimated initial margin exceeded
     * available funds. No broker order was submitted — opposite of {@link #FAILED_BROKER_REJECT}
     * where IBKR rejects after submission.
     */
    SKIPPED_INSUFFICIENT_MARGIN,
    /** The broker rejected the order — generic / legacy fallback. Prefer the typed variants below. */
    FAILED,
    /**
     * IBKR did not acknowledge the order within the configured wait window.
     * Broker state is <b>unknown</b>: the order may have been received but the
     * {@code orderStatus} callback was lost. The corresponding execution row is
     * left non-terminal so the live position stays visible and the operator can
     * reconcile manually.
     */
    FAILED_TIMEOUT,
    /**
     * IBKR explicitly rejected the order (Cancelled / ApiCancelled / Inactive /
     * non-margin {@code rejectReason}). Distinct from {@link #SKIPPED_INSUFFICIENT_MARGIN}
     * (which is decided pre-flight) and from {@link #FAILED_TIMEOUT} (where the state is unknown).
     */
    FAILED_BROKER_REJECT
}
