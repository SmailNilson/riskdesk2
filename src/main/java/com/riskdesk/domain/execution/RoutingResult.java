package com.riskdesk.domain.execution;

/**
 * Result of {@code OrderRouter.route}: the {@link RoutingOutcome}, an optional human-readable reason
 * (surfaced in signal history / UI tooltips), the <b>internal execution-row id</b> (the persisted
 * {@code trade_executions} primary key the strategy links back to — e.g. Playbook stores it in
 * {@code PlaybookDecision.executionId} and the UI surfaces it), and the broker (IBKR) order id when
 * one was assigned.
 *
 * <p>{@code executionId} and {@code brokerOrderId} are distinct and not substitutes: the broker id
 * can be {@code null} on outcomes like {@link RoutingOutcome#FAILED_TIMEOUT} (the order may be live
 * but unacked), while the execution-row id still links the caller to the tracked row.</p>
 *
 * @param outcome       the routing outcome (required).
 * @param message       optional human-readable detail, e.g. a reject reason.
 * @param executionId   internal execution-row id (persisted {@code trade_executions} PK), or {@code null}
 *                      if no row was created.
 * @param brokerOrderId IBKR order id when the order reached the broker, else {@code null}.
 */
public record RoutingResult(RoutingOutcome outcome, String message, Long executionId, Long brokerOrderId) {

    public RoutingResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
    }

    public static RoutingResult of(RoutingOutcome outcome) {
        return new RoutingResult(outcome, null, null, null);
    }

    public static RoutingResult of(RoutingOutcome outcome, String message) {
        return new RoutingResult(outcome, message, null, null);
    }

    /** Result that links to a persisted execution row (and the IBKR order id when known). */
    public static RoutingResult tracked(RoutingOutcome outcome, Long executionId, Long brokerOrderId) {
        return new RoutingResult(outcome, null, executionId, brokerOrderId);
    }

    /** As {@link #tracked(RoutingOutcome, Long, Long)} with a human-readable message. */
    public static RoutingResult tracked(RoutingOutcome outcome, String message, Long executionId, Long brokerOrderId) {
        return new RoutingResult(outcome, message, executionId, brokerOrderId);
    }
}
