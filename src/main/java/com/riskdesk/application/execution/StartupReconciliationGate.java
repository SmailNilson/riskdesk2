package com.riskdesk.application.execution;

import org.springframework.stereotype.Component;

/**
 * Default {@link ExecutionReadinessGate} bean.
 *
 * <p><b>Current state:</b> permanently open. The {@link DefaultOrderRouter} is not wired to any live
 * strategy yet, so nothing routes through it; opening the gate is a no-op until then. A later step
 * (see docs/PLAN_ORDER_ROUTER_IMPL.md, "RECONCILING boot gate") adds an {@code ApplicationReadyEvent}
 * listener that closes the gate at startup, replays the {@code ENTRY_SUBMITTED} reconciliation, then
 * opens it — so the field + {@link #setReady(boolean)} hook already exist for that wiring.</p>
 */
@Component
public class StartupReconciliationGate implements ExecutionReadinessGate {

    private volatile boolean ready = true;

    @Override
    public boolean isReady() {
        return ready;
    }

    /** Package-private hook for the boot-reconciliation listener (and tests). */
    void setReady(boolean ready) {
        this.ready = ready;
    }
}
