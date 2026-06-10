package com.riskdesk.domain.orderflow.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FootprintBar;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for accessing footprint chart data.
 * Implemented by the infrastructure adapter that aggregates classified ticks
 * into per-price-bucket bid/ask volume profiles over clock-aligned bars.
 */
public interface FootprintPort {

    /**
     * Returns the current (in-progress) clock-aligned footprint bar for the
     * given instrument. The bar's timeframe and price-bucket size are fixed
     * by configuration (e.g. 10m bars, 5.00-point buckets for MNQ).
     *
     * @param instrument the instrument to query
     * @return the current footprint bar, or empty if no tick data is available
     */
    Optional<FootprintBar> currentBar(Instrument instrument);

    /**
     * Closes any bar whose window has fully elapsed without being rolled over by
     * a new tick (quiet market). Each closed bar is also published as a
     * {@code FootprintBarClosed} domain event by the implementation.
     *
     * @param now the current wall-clock time
     * @return the bars closed by this sweep (possibly empty)
     */
    List<FootprintBar> closeElapsedBars(Instant now);
}
