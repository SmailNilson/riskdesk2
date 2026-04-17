package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MechanicalPlan;
import com.riskdesk.domain.engine.strategy.model.StrategyInput;

import java.util.Optional;

/**
 * A named institutional setup (e.g. "LSAR", "SBDR"). A playbook owns three things:
 *
 * <ol>
 *   <li>An applicability predicate ({@link #isApplicable(MarketContext)}) that the
 *       selector uses to pick a single candidate per evaluation.</li>
 *   <li>A mechanical plan builder ({@link #buildPlan(StrategyInput)}) — pure function
 *       of input → entry/SL/TP, returning empty when the plan cannot be constructed
 *       (e.g. no OB nearby).</li>
 *   <li>A minimum-score threshold ({@link #minimumScoreForExecution()}) that the
 *       scoring policy compares against {@code |finalScore|} before declaring the
 *       decision tradeable.</li>
 * </ol>
 *
 * <p>Playbooks are stateless — one instance per JVM.
 */
public interface Playbook {

    String id();

    boolean isApplicable(MarketContext context);

    Optional<MechanicalPlan> buildPlan(StrategyInput input);

    /** Minimum {@code |finalScore|} (0..100) required to exit MONITORING / PAPER_TRADE. */
    double minimumScoreForExecution();
}
