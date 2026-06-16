package com.riskdesk.domain.orderflow.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.TickBar;

import java.time.Instant;
import java.util.List;

/**
 * Domain port for durably storing completed tick-chart bars so the tick chart
 * survives a backend restart/redeploy.
 *
 * <p>The in-memory {@link com.riskdesk.domain.orderflow.service.TickBarAggregator}
 * ring buffer is volatile — on a redeploy it starts empty and the chart is blank
 * until fresh trades arrive. This port lets the infrastructure adapter persist each
 * completed bar and reload the most recent ones on startup, restoring history and a
 * monotonic {@code seq} so the frontend (which merges by seq) sees a seamless chart.</p>
 */
public interface TickBarStorePort {

    /**
     * Persist completed bars (idempotent on {@code (instrument, ticksPerBar, seq)}).
     * Only complete bars should be passed; the in-progress bar is never stored.
     *
     * @param bars completed bars to append
     */
    void saveCompleted(List<TickBar> bars);

    /**
     * The most recent persisted bars for an instrument at a given bar size, oldest
     * first — used to re-seed the in-memory ring buffer on startup.
     *
     * @param instrument  the instrument to load
     * @param ticksPerBar bar size; bars built with a different size are excluded
     * @param limit       max bars returned (newest kept when more exist)
     * @return up to {@code limit} completed bars, ascending by seq; empty when none
     */
    List<TickBar> loadRecent(Instrument instrument, int ticksPerBar, int limit);

    /**
     * Drop all persisted bars for an instrument — called on a contract rollover so a
     * later restart does not reload bars priced on the expired contract.
     *
     * @param instrument the instrument whose bars to delete
     */
    void purgeInstrument(Instrument instrument);

    /**
     * Delete persisted bars whose close time is before the cutoff (retention purge).
     *
     * @param cutoff delete bars that closed before this instant
     * @return number of bars deleted
     */
    int purgeOlderThan(Instant cutoff);
}
