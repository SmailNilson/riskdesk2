package com.riskdesk.domain.quant.structure;

import com.riskdesk.domain.model.Instrument;

import java.util.Optional;

/**
 * Domain port surfacing the strategy votes / decision projection consumed by
 * {@link StructuralFilterEvaluator}. Same 5m-only contract as
 * {@link IndicatorsPort}.
 */
public interface StrategyPort {

    /**
     * @return the most recent strategy projection for the instrument, or
     *         {@link Optional#empty()} when the strategy engine has not
     *         produced a verdict yet.
     */
    Optional<StrategyVotes> votes5m(Instrument instrument);
}
