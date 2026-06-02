package com.riskdesk.application.execution;

import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.model.Side;

/**
 * The resolved plan after reconciling a {@code TradeIntent} against live broker position truth
 * ({@link ExecutionReconciler#reconcile}). The router switches on this to execute — the reconcile
 * step decides WHAT to do (incl. upgrade OPEN→REVERSE, downgrade REVERSE→OPEN, or skip), the router
 * decides HOW (submits the legs).
 */
public sealed interface ReconcilePlan {

    /** Do not route — return this outcome (e.g. already in position, or flat on a close/flatten). */
    record Skip(RoutingOutcome outcome, String message) implements ReconcilePlan {}

    /** Open a new position on {@code side} (no opposite position to flatten first). */
    record Open(Side side) implements ReconcilePlan {}

    /** Flip: close the opposite position, then open on {@code toSide} — two 1:1 legs. */
    record Reverse(Side toSide) implements ReconcilePlan {}

    /** Close the position on {@code side}. */
    record Close(Side side) implements ReconcilePlan {}

    /** Flatten the held position ({@code heldSide} derived from broker truth). */
    record Flatten(Side heldSide) implements ReconcilePlan {}
}
