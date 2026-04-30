package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.model.Instrument;

import java.util.Optional;

/**
 * Domain port for accessing real-time tick-by-tick trade data aggregations.
 * Infrastructure adapters implement this to provide aggregated order flow data
 * from IBKR tick-by-tick feeds or CLV-based candle estimation as fallback.
 */
public interface TickDataPort {

    /**
     * Returns the current tick aggregation for the given instrument,
     * or empty if no aggregation data is available.
     */
    Optional<TickAggregation> currentAggregation(Instrument instrument);

    /**
     * Returns an aggregation over only the last {@code windowSeconds} of ticks.
     * <p>
     * Used by short-window detectors (absorption, momentum) that need a tight time
     * frame to detect transient events. Implementations without windowing support
     * fall back to {@link #currentAggregation(Instrument)}.
     */
    default Optional<TickAggregation> recentAggregation(Instrument instrument, long windowSeconds) {
        return currentAggregation(instrument);
    }

    /**
     * Returns true if real tick-by-tick data (not CLV estimation) is currently
     * available for this instrument.
     */
    boolean isRealTickDataAvailable(Instrument instrument);
}
