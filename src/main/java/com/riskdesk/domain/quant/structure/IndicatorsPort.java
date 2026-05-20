package com.riskdesk.domain.quant.structure;

import com.riskdesk.domain.model.Instrument;

import java.util.Optional;

/**
 * Domain port surfacing the indicator projection consumed by
 * {@link StructuralFilterEvaluator}. The 5m timeframe is the only one used
 * by the structural filters (matches the Python reference
 * {@code multi_scan_v4.py}).
 */
public interface IndicatorsPort {

    /**
     * @return the most recent indicators projection for the instrument, or
     *         {@link Optional#empty()} if no candle has produced a snapshot
     *         yet (e.g. cold start).
     */
    Optional<IndicatorsSnapshot> snapshot5m(Instrument instrument);
}
