package com.riskdesk.application.execution;

import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;

/**
 * The single entry point through which EVERY strategy submits to the broker. Implementations own the
 * shared mechanics: idempotence on {@link TradeIntent#idempotencyKey()}, a startup RECONCILING gate,
 * permId-keyed broker-truth reconciliation, minTick rounding and typed error mapping.
 *
 * <p>A new strategy only needs to build a {@link TradeIntent}, register an
 * {@link com.riskdesk.domain.model.ExecutionTriggerSource}, and call {@link #route(TradeIntent)} —
 * it never touches IBKR infrastructure, reconciliation or idempotence directly.</p>
 *
 * <p>Contract defined by {@code docs/ADR_UNIFIED_EXECUTION_CORE.md} (Phase 2, step 1). The
 * implementation and the WTX pilot migration land in later steps.</p>
 */
public interface OrderRouter {

    /**
     * Route an intent to the broker. Never throws for an expected broker/gating condition — those are
     * reported via {@link RoutingResult#outcome()} so callers can branch deterministically.
     */
    RoutingResult route(TradeIntent intent);
}
