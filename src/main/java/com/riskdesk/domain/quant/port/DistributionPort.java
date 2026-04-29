package com.riskdesk.domain.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DistributionSignal;

import java.time.Instant;
import java.util.List;

/**
 * Domain port returning recently detected institutional distribution /
 * accumulation signals for an instrument.
 */
public interface DistributionPort {

    /** Distribution / accumulation signals at or after {@code since}, newest first. */
    List<DistributionSignal> recent(Instrument instrument, Instant since);
}
