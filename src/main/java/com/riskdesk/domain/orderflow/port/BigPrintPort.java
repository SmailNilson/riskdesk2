package com.riskdesk.domain.orderflow.port;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Domain port for big-print (outsized trade) detection state.
 * Implemented by the infrastructure adapter that routes classified AllLast ticks
 * into per-instrument {@code BigPrintDetector} instances.
 */
public interface BigPrintPort {

    /**
     * Net signed volume (buy − sell) of flagged big prints over the trailing 5 minutes,
     * as of {@code now}. Returns 0 when no detector exists for the instrument.
     */
    long bigPrintDelta5m(Instrument instrument, Instant now);
}
