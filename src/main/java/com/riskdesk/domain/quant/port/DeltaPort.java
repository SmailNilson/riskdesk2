package com.riskdesk.domain.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.DeltaSnapshot;

import java.util.Optional;

/**
 * Domain port returning the current cumulative delta and buy ratio aggregate
 * for an instrument. Backed by tick aggregations (real or CLV-estimated).
 */
public interface DeltaPort {

    Optional<DeltaSnapshot> current(Instrument instrument);
}
