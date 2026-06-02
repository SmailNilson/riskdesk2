package com.riskdesk.domain.execution;

/**
 * Strategy-neutral outcome of routing a {@link TradeIntent} through the execution core. Generalises
 * the per-strategy {@code WtxRoutingOutcome} so every strategy reports the same vocabulary, surfaced
 * in signal history / WS payloads / UI. Each value carries a {@link Category} so callers branch on
 * success vs skip vs failure without string / prefix matching.
 */
public enum RoutingOutcome {

    /** Order(s) submitted to the broker. */
    ROUTED(Category.SUCCESS),
    /** A REVERSE flattened the prior position but the open leg was intentionally not sent (broker FLAT). */
    ROUTED_FLATTEN_ONLY(Category.SUCCESS),
    /** Order reached the broker (has an id) but the first ack was late; fill callbacks reconcile it. */
    ACK_PENDING(Category.SUCCESS),

    /** Auto-execution is OFF for this (instrument, timeframe). */
    SKIPPED_AUTO_OFF(Category.SKIPPED),
    /** IBKR / execution disabled, or the gateway is not wired. */
    SKIPPED_DISABLED(Category.SKIPPED),
    /** An execution already exists for this {@link TradeIntent#idempotencyKey()}. */
    SKIPPED_DUPLICATE(Category.SKIPPED),
    /** The core is still reconciling broker truth at startup — the intent is refused, not queued. */
    SKIPPED_RECONCILING(Category.SKIPPED),
    /** Broker already flat / nothing to do (e.g. CLOSE with no position). */
    SKIPPED_FLAT(Category.SKIPPED),
    /** No reference price available to build the Limit order. */
    SKIPPED_NO_PRICE(Category.SKIPPED),

    /** Margin / equity / buying-power rejected the order (no position change occurred). */
    FAILED_INSUFFICIENT_MARGIN(Category.FAILED),
    /** Broker rejected the order (non-margin) or cancelled it. */
    FAILED_BROKER_REJECT(Category.FAILED),
    /** No acknowledgement and no broker order id — the order never reached IBKR. */
    FAILED_TIMEOUT(Category.FAILED),
    /** Order blocked: the API session is read-only (software kill-switch or TWS Read-Only mode). */
    FAILED_READ_ONLY(Category.FAILED),
    /** Catch-all failure. */
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
