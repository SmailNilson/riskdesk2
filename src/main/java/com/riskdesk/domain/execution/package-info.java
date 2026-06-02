/**
 * Strategy-neutral execution contract — the {@link com.riskdesk.domain.execution.TradeIntent} a
 * strategy hands to the execution core, and the {@link com.riskdesk.domain.execution.RoutingResult}
 * it gets back. Pure domain: no Spring, no JPA, no IBKR SDK.
 *
 * <p>This is the foundation of the unified execution core (Phase 2). A new strategy emits a
 * {@code TradeIntent}, registers an {@link com.riskdesk.domain.model.ExecutionTriggerSource}, and
 * calls {@code OrderRouter.route} — it never touches IBKR infrastructure, reconciliation or
 * idempotence directly. See {@code docs/ADR_UNIFIED_EXECUTION_CORE.md}.</p>
 */
package com.riskdesk.domain.execution;
