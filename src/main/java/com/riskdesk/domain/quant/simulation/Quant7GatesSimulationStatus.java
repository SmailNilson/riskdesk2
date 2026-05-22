package com.riskdesk.domain.quant.simulation;

/**
 * Lifecycle states of a {@link Quant7GatesSimulation}.
 *
 * <ul>
 *   <li>{@link #OPEN} — entry filled, awaiting exit. Snapshot ticks
 *       mark-to-market the row via {@code markToMarket}.</li>
 *   <li>{@link #CLOSED_FLOW_AVOID} — pattern flipped to {@code AVOID} for the
 *       trade direction, harness closed the position at the live price.</li>
 *   <li>{@link #CLOSED_TP1} — live price touched the configured TP1 offset.</li>
 *   <li>{@link #CLOSED_TP2} — live price touched the configured TP2 offset.</li>
 *   <li>{@link #CLOSED_SL} — live price touched the configured stop-loss
 *       offset on the adverse side.</li>
 * </ul>
 */
public enum Quant7GatesSimulationStatus {
    OPEN,
    CLOSED_FLOW_AVOID,
    CLOSED_TP1,
    CLOSED_TP2,
    CLOSED_SL
}
