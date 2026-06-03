package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;

/**
 * Mirrors the Quant 7-Gates simulation harness to IBKR.
 *
 * <p>The bridge is the only seam where the simulation touches a broker. The
 * service pulls it via {@code ObjectProvider<Quant7GatesExecutionBridge>}: when
 * the bean is not wired ({@code riskdesk.quant.sim-exec.enabled=false} or IBKR
 * mode off) the simulation runs paper-only and the provider yields {@code null},
 * so the harness short-circuits with no broker side effect.</p>
 *
 * <p>Returns the strategy-neutral {@link RoutingResult} from the unified
 * execution core — this path will migrate behind {@code OrderRouter} in Phase 2b
 * (see {@code docs/ADR_UNIFIED_EXECUTION_CORE.md}) alongside the other sources.</p>
 *
 * <p>Contract — entry-Limit only, no resident broker stop; the broker position
 * follows the paper trade 1:1:</p>
 * <ul>
 *   <li>{@link #submitOpen} routes one Limit entry for a freshly-opened paper
 *       trade, gated by the allowlist + per-instrument toggle + a
 *       one-position-per-instrument guard.</li>
 *   <li>{@link #submitClose} flattens the bridge's own open row when the paper
 *       trade resolves (SL / TP / flow-AVOID). It is intentionally NOT gated by
 *       the toggle/allowlist — once a real position exists it must always be
 *       closable, even if the operator flipped the toggle off meanwhile.</li>
 * </ul>
 */
public interface Quant7GatesExecutionBridge {

    /** Route a Limit entry order mirroring a freshly-opened paper simulation. */
    RoutingResult submitOpen(Quant7GatesSimulation opened);

    /** Flatten the open IBKR position mirroring a just-resolved paper simulation. */
    RoutingResult submitClose(Quant7GatesSimulation closed);
}
