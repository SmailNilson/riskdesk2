package com.riskdesk.domain.engine.strategy;

import com.riskdesk.domain.engine.strategy.model.StrategyDecision;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;

/**
 * Pure-domain port for the unified strategy engine. The application layer wires a
 * concrete implementation (see {@code StrategyEngineService}) to the live data
 * sources; tests construct {@link StrategyInput} directly and call
 * {@link #evaluate(StrategyInput)}.
 */
public interface StrategyEngine {

    StrategyDecision evaluate(StrategyInput input);
}
