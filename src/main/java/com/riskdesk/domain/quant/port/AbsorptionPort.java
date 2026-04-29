package com.riskdesk.domain.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;

import java.time.Instant;
import java.util.List;

/**
 * Domain port returning recently detected absorption events for an instrument.
 * Implementations live in {@code infrastructure} and adapt the persisted
 * order-flow stream into pure domain models.
 */
public interface AbsorptionPort {

    /**
     * Returns absorption signals whose timestamp is at or after {@code since},
     * newest first. Implementations may apply an upper-bound limit.
     */
    List<AbsorptionSignal> recent(Instrument instrument, Instant since);
}
