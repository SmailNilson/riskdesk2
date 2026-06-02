package com.riskdesk.domain.execution.port;

import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;

/**
 * Supplies the minimum price increment (tick) for an instrument used to normalise order prices before
 * submission. The broker's authoritative {@code ContractDetails.minTick} is preferred at runtime; the
 * implementation falls back to the instrument's hardcoded {@link Instrument#getTickSize()} when broker
 * truth is not available (cold cache, non-gateway mode), so the result is always a valid positive tick.
 *
 * <p>Functional interface so tests can pass {@code Instrument::getTickSize} directly.</p>
 */
@FunctionalInterface
public interface InstrumentTickProvider {

    /** The tick size to round order prices to — never null, always {@code > 0}. */
    BigDecimal minTick(Instrument instrument);
}
