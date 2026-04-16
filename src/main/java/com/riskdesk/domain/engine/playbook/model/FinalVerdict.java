package com.riskdesk.domain.engine.playbook.model;

import com.riskdesk.domain.engine.playbook.agent.AgentVerdict;

import java.util.List;

/**
 * Final orchestrator decision for a setup.
 *
 * <p>{@code sizePercent} is a <b>risk fraction</b> (not a display percent).
 * See {@link RiskFraction} for the unit contract. {@code 0.01} means 1% of
 * equity at risk. The compact constructor rejects out-of-range values so a
 * percent-vs-fraction bug cannot silently propagate to {@code TradeDecision}
 * persistence.
 */
public record FinalVerdict(
    String verdict,
    PlaybookPlan adjustedPlan,
    double sizePercent,
    List<AgentVerdict> agentVerdicts,
    List<String> warnings,
    String eligibility
) {
    public FinalVerdict {
        RiskFraction.requireValid(sizePercent);
    }

    /**
     * {@link #sizePercent} expressed as a display percent, e.g. {@code 0.01 → 1.0}.
     * Use at log / UI / narration boundaries.
     */
    public double sizePercentDisplay() {
        return RiskFraction.toPercent(sizePercent);
    }
}
