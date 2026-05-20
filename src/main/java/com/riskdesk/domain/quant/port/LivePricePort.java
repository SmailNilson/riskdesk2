package com.riskdesk.domain.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;

import java.util.Optional;

/**
 * Domain port returning the latest live price snapshot for an instrument,
 * including its provenance ({@code LIVE_PUSH}, fallback, ...).
 */
public interface LivePricePort {

    Optional<LivePriceSnapshot> current(Instrument instrument);
}
