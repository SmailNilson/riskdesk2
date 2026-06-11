package com.riskdesk.domain.quant.scanlog;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.List;

/**
 * Durable store for the per-scan Quant flow log. Implemented by a JPA adapter
 * (`quant_scan_snapshots`, 90-day retention purged with the order-flow event
 * tables).
 */
public interface QuantScanSnapshotPort {

    /** Appends one scan row (best-effort caller — must never break the scan). */
    void save(QuantScanSnapshot snapshot);

    /** Rows for the instrument in {@code [from, to]}, newest first, capped at {@code limit}. */
    List<QuantScanSnapshot> findRange(Instrument instrument, Instant from, Instant to, int limit);

    /** Deletes rows scanned before {@code cutoff}; returns the count removed. */
    int purgeBefore(Instant cutoff);
}
