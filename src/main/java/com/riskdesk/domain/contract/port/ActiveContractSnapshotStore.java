package com.riskdesk.domain.contract.port;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.Optional;

/**
 * Persists the last-known-good active contract month per instrument, so that
 * a cold boot without IBKR connectivity does not fall back to a stale value
 * hardcoded in {@code application.properties}.
 *
 * <p>Writes happen on:
 * <ul>
 *   <li>Successful IBKR contract resolution at startup (OI, volume, or front-month).</li>
 *   <li>Operator or auto-confirmed rollover via {@code POST /api/rollover/confirm}.</li>
 * </ul>
 *
 * <p>Reads happen at startup when IBKR is unreachable — the snapshot is preferred
 * over the hardcoded properties fallback because it reflects the most recent
 * contract the system actually observed.
 */
public interface ActiveContractSnapshotStore {

    /**
     * Loads the last persisted snapshot for the given instrument.
     *
     * @return the snapshot, or empty if never persisted.
     */
    Optional<Snapshot> load(Instrument instrument);

    /**
     * Upserts the active contract month for an instrument.
     */
    void save(Instrument instrument, String contractMonth, Source source, Instant resolvedAt);

    /**
     * Provenance of a snapshot — kept for forensic analysis only.
     * Not used for business logic; all sources are treated equally when restoring state.
     */
    enum Source {
        /** Resolved from IBKR via Open Interest comparison across contracts. */
        IBKR_OI,
        /** Resolved from IBKR via trading-volume comparison (OI unavailable). */
        IBKR_VOLUME,
        /** Resolved from IBKR using the first-returned front-month (OI + volume unavailable). */
        IBKR_FRONT,
        /** Persisted when a rollover is confirmed (manual via API or auto via OI monitor). */
        ROLLOVER_CONFIRM
    }

    record Snapshot(Instrument instrument, String contractMonth, Source source, Instant resolvedAt) {}
}
