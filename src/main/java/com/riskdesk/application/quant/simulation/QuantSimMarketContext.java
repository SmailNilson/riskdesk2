package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;

/**
 * Read-only market context the Quant 7-Gates simulation harness consults at
 * entry time — the instrument's current ATR (for ATR-sized SL/TP) and whether
 * the higher-timeframe trend agrees with the proposed direction.
 *
 * <p>Interface so the harness can be unit-tested with stubbed values without a
 * candle store.</p>
 */
public interface QuantSimMarketContext {

    /**
     * Latest ATR on the configured timeframe/period, or {@code null} when the
     * candle history is too short to compute one.
     */
    Double atr(Instrument instrument);

    /**
     * Whether the higher-timeframe EMA pair agrees with {@code direction}
     * (fast above slow for LONG, below for SHORT). {@code null} when the
     * candle history is too short — callers treat that as "not aligned"
     * (fail-closed: the filter exists to block toxic counter-trend entries).
     */
    Boolean htfAligned(Instrument instrument, Quant7GatesSimulation.Direction direction);
}
