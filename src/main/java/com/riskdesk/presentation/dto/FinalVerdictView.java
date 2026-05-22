package com.riskdesk.presentation.dto;

import com.riskdesk.domain.engine.playbook.agent.AgentVerdict;
import com.riskdesk.domain.engine.playbook.model.FinalVerdict;
import com.riskdesk.domain.model.Instrument;

import java.util.List;

/**
 * Presentation view of {@link FinalVerdict}. Mirrors the domain record but
 * replaces the {@code adjustedPlan} with {@link PlaybookPlanView} so the
 * client receives the contract spec alongside the agent-adjusted plan.
 */
public record FinalVerdictView(
    String verdict,
    PlaybookPlanView adjustedPlan,
    double sizePercent,
    List<AgentVerdict> agentVerdicts,
    List<String> warnings,
    String eligibility
) {
    public static FinalVerdictView from(FinalVerdict verdict, Instrument instrument) {
        if (verdict == null) return null;
        return new FinalVerdictView(
            verdict.verdict(),
            PlaybookPlanView.from(verdict.adjustedPlan(), instrument),
            verdict.sizePercent(),
            verdict.agentVerdicts(),
            verdict.warnings(),
            verdict.eligibility()
        );
    }
}
