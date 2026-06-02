package com.riskdesk.application.execution;

/**
 * Startup gate for the execution core. Until the core has reconciled broker truth at boot, the
 * {@link OrderRouter} must refuse intents (returns {@code SKIPPED_RECONCILING}) so a signal firing
 * right after a restart cannot double-submit against an order the broker already holds.
 *
 * <p>Functional interface so tests can pass a lambda. The real boot-reconciliation implementation
 * ({@link StartupReconciliationGate}) is wired in a later step (see docs/PLAN_ORDER_ROUTER_IMPL.md).</p>
 */
@FunctionalInterface
public interface ExecutionReadinessGate {

    /** {@code true} once the core has reconciled with the broker and may route intents. */
    boolean isReady();
}
