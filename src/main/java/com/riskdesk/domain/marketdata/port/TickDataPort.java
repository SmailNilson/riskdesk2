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
     * Returns true if real tick-by-tick data (not CLV estimation) is currently
     * available for this instrument.
     */
    boolean isRealTickDataAvailable(Instrument instrument);
}
