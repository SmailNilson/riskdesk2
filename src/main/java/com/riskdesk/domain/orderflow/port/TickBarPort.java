package com.riskdesk.domain.orderflow.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.TickBar;

import java.util.List;

/**
 * Domain port for the tick chart: constant-tick-count bars built from classified
 * trades. Implemented by the infrastructure adapter that owns the per-instrument
 * {@code TickBarAggregator} ring buffers.
 */
public interface TickBarPort {

    /**
     * The most recent tick bars, oldest first — completed bars followed by the
     * in-progress bar ({@code complete=false}) when it has trades.
     *
     * @param instrument the instrument to query
     * @param limit      max bars returned
     * @return up to {@code limit} bars, empty when no tick data yet
     */
    List<TickBar> recentBars(Instrument instrument, int limit);
}
