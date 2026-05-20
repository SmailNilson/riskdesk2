package com.riskdesk.domain.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;

import java.time.Instant;
import java.util.List;

/**
 * Domain port returning recent smart-money cycle events for an instrument.
 */
public interface CyclePort {

    /** Cycle events at or after {@code since}, newest first. */
    List<SmartMoneyCycleSignal> recent(Instrument instrument, Instant since);
}
