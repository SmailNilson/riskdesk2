package com.riskdesk.domain.quant.simulation.port;

import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;

import java.util.List;

/**
 * Output port for durably persisting {@link Quant7GatesSimulation} rows so the
 * harness history survives a backend restart and the "since the beginning"
 * P&amp;L report stays meaningful beyond the in-memory ring buffer.
 *
 * <p>The application service keeps OPEN rows in memory for live
 * mark-to-market, but every OPEN (insert) and terminal CLOSE (update) is
 * mirrored here. Reads for the full closed history come straight from the
 * durable store rather than the capped in-memory buckets.
 *
 * <p>This port is intentionally separate from any Mentor / trade-simulation
 * persistence — it backs the quant-evaluator validation harness only, in line
 * with the "Simulation Decoupling Rule" in CLAUDE.md.
 */
public interface Quant7GatesSimulationRepositoryPort {

    /** Inserts a new row or updates the existing one keyed by {@code simulation.id()}. */
    void save(Quant7GatesSimulation simulation);

    /** Every resolved (non-OPEN) row — the full closed history for reports/stats. */
    List<Quant7GatesSimulation> findAllClosed();

    /** Every still-OPEN row — used to rehydrate in-flight trades on startup. */
    List<Quant7GatesSimulation> findAllOpen();

    /** Highest persisted id (0 when empty) so the service can seed its sequence after a restart. */
    long maxId();
}
