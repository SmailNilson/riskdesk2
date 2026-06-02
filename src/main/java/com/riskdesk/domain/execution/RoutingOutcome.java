package com.riskdesk.domain.execution;

/**
 * Strategy-neutral outcome of routing a {@link TradeIntent} through the execution core. This is the
 * replacement vocabulary for every strategy, so it is a faithful <b>superset</b> of the per-strategy
 * {@code WtxRoutingOutcome} — no behavioural signal is lost when WTX (or any strategy) migrates onto
 * it. Each value carries a {@link Category} so callers branch on success vs skip vs failure without
 * string / prefix matching.
 *
 * <p>Two values require the <b>caller</b> to realign its optimistic virtual position state — switch
 * on the exact value, the {@link Category} alone is not enough: {@link #ROUTED_FLATTEN_ONLY} (correct
 * to FLAT) and {@link #SKIPPED_ENTRY_IN_FLIGHT} (revert to the pre-action side).</p>
 */
public enum RoutingOutcome {

    /** Order(s) submitted to the broker (entry, close, or both legs of a reverse). */
    ROUTED(Category.SUCCESS),
    /**
     * A REVERSE flattened the prior position (close leg sent) but the open leg was intentionally not
     * sent (e.g. its net margin delta was unaffordable). The broker ends up <b>FLAT</b>. Caller must
     * correct the virtual state to FLAT — it must NOT leave it on the new (never-opened) side.
     */
    ROUTED_FLATTEN_ONLY(Category.SUCCESS),
    /** Order reached the broker (has an id) but the first ack was late; fill callbacks reconcile it. */
    ACK_PENDING(Category.SUCCESS),

    /** Auto-execution is OFF for this (instrument, timeframe). */
    SKIPPED_AUTO_OFF(Category.SKIPPED),
    /** Execution is not active — the bridge/router bean is not wired, or IBKR is disabled in config. */
    SKIPPED_DISABLED(Category.SKIPPED),
    /** An execution already exists for this {@link TradeIntent#idempotencyKey()}. */
    SKIPPED_DUPLICATE(Category.SKIPPED),
    /** The core is still reconciling broker truth at startup — the intent is refused, not queued. */
    SKIPPED_RECONCILING(Category.SKIPPED),
    /** No reference price available to build the Limit order. */
    SKIPPED_NO_PRICE(Category.SKIPPED),
    /** Resulting position quantity was non-positive. */
    SKIPPED_NO_QTY(Category.SKIPPED),
    /** A close / reverse was requested but no open execution row exists for this (instrument, timeframe). */
    SKIPPED_NO_OPEN_ROW(Category.SKIPPED),
    /**
     * An OPEN / REVERSE was skipped because a prior entry order is still <b>in flight</b> (a
     * non-terminal {@code ENTRY_SUBMITTED} / {@code ENTRY_PARTIALLY_FILLED} row) while IBKR reads
     * flat — the resting order simply hasn't filled yet. Submitting a second entry would risk a
     * double fill, so no broker order was sent. The caller must <b>revert</b> the optimistically
     * applied virtual state to its pre-action value: the only live order is the resting one, so the
     * panel must keep pointing at that side, not the never-opened new side. (Prevents the
     * double-fill / naked-flatten scenarios covered by the WTX bridge tests — see PR #368/#372.)
     */
    SKIPPED_ENTRY_IN_FLIGHT(Category.SKIPPED),
    /**
     * Pre-flight margin check denied the order: estimated initial margin exceeded available funds.
     * No broker order was submitted — distinct from {@link #FAILED_INSUFFICIENT_MARGIN}, which is an
     * IBKR rejection <i>after</i> submission.
     */
    SKIPPED_INSUFFICIENT_MARGIN(Category.SKIPPED),

    /** IBKR rejected the order on margin / equity / buying-power <i>after</i> submission (e.g. code 201). */
    FAILED_INSUFFICIENT_MARGIN(Category.FAILED),
    /** Broker rejected the order (Cancelled / ApiCancelled / Inactive / non-margin reject reason). */
    FAILED_BROKER_REJECT(Category.FAILED),
    /** No acknowledgement within the wait window; broker state is unknown — leave the row non-terminal. */
    FAILED_TIMEOUT(Category.FAILED),
    /** Order blocked: the API session is read-only (software kill-switch or TWS Read-Only mode). */
    FAILED_READ_ONLY(Category.FAILED),
    /** Catch-all failure — prefer a typed variant above. */
    FAILED(Category.FAILED);

    /** Coarse bucket for branching without enumerating every constant. */
    public enum Category { SUCCESS, SKIPPED, FAILED }

    private final Category category;

    RoutingOutcome(Category category) {
        this.category = category;
    }

    public Category category() {
        return category;
    }

    public boolean isSuccess() {
        return category == Category.SUCCESS;
    }

    public boolean isSkipped() {
        return category == Category.SKIPPED;
    }

    public boolean isFailure() {
        return category == Category.FAILED;
    }

    /** True when an order actually reached the broker, so the caller must track an execution row. */
    public boolean orderReachedBroker() {
        return this == ROUTED || this == ROUTED_FLATTEN_ONLY || this == ACK_PENDING;
    }
}
