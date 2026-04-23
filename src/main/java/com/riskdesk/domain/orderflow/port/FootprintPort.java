package com.riskdesk.domain.orderflow.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FootprintBar;

import java.util.Optional;

/**
 * Domain port for accessing footprint chart data.
 * Implemented by the infrastructure adapter that aggregates classified ticks
 * into per-price-level bid/ask volume profiles.
 */
public interface FootprintPort {

    /**
     * Returns the current (in-progress) footprint bar for the given instrument and timeframe.
     *
     * @param instrument the instrument to query
     * @param timeframe  the bar timeframe (e.g. "5m")
     * @return the current footprint bar, or empty if no data is available
     */
    Optional<FootprintBar> currentBar(Instrument instrument, String timeframe);
}
