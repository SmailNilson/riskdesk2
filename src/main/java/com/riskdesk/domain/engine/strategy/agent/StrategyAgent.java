package com.riskdesk.domain.engine.strategy.agent;

import com.riskdesk.domain.engine.strategy.model.AgentVote;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;
import com.riskdesk.domain.engine.strategy.model.StrategyLayer;

/**
 * A single contribution to the probabilistic decision engine.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Be pure functions of the input — no stateful fields, no side effects, no Spring.</li>
 *   <li>Respect their declared {@link #layer()} — a CONTEXT agent never inspects
 *       {@link StrategyInput#trigger()}; a TRIGGER agent never re-evaluates HTF bias.</li>
 *   <li>Return {@link AgentVote#abstain(String, StrategyLayer, String)} when they
 *       don't have enough data to emit a meaningful vote — never fake a neutral vote.</li>
 * </ul>
 */
public interface StrategyAgent {

    /** Stable, human-readable identifier. Shown in evidence, logged, persisted. */
    String id();

    StrategyLayer layer();

    AgentVote evaluate(StrategyInput input);
}
