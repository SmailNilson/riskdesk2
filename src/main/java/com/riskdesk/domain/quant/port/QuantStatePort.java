package com.riskdesk.domain.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.QuantState;

/**
 * Output port persisting the per-instrument {@link QuantState} between scans.
 */
public interface QuantStatePort {

    /** Returns the most recently saved state, or {@code null} if none exists yet. */
    QuantState load(Instrument instrument);

    /** Overwrites the saved state for the instrument. */
    void save(Instrument instrument, QuantState state);
}
