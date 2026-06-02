package com.riskdesk.domain.execution;

/**
 * Result of {@code OrderRouter.route}: the {@link RoutingOutcome}, an optional human-readable reason
 * (surfaced in signal history / UI tooltips), and the broker order id when one was assigned.
 *
 * @param outcome       the routing outcome (required).
 * @param message       optional human-readable detail, e.g. a reject reason.
 * @param brokerOrderId IBKR order id when the order reached the broker, else {@code null}.
 */
public record RoutingResult(RoutingOutcome outcome, String message, Long brokerOrderId) {

    public RoutingResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
    }

    public static RoutingResult of(RoutingOutcome outcome) {
        return new RoutingResult(outcome, null, null);
    }

    public static RoutingResult of(RoutingOutcome outcome, String message) {
        return new RoutingResult(outcome, message, null);
    }

    public static RoutingResult of(RoutingOutcome outcome, String message, Long brokerOrderId) {
        return new RoutingResult(outcome, message, brokerOrderId);
    }
}
