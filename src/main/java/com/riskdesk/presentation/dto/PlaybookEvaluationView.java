package com.riskdesk.presentation.dto;

import com.riskdesk.domain.engine.playbook.model.ChecklistItem;
import com.riskdesk.domain.engine.playbook.model.FilterResult;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.List;

/**
 * Presentation view of {@link PlaybookEvaluation}. Identical to the domain
 * record except {@code plan} is replaced with {@link PlaybookPlanView} so the
 * client receives contract spec alongside the trade plan.
 */
public record PlaybookEvaluationView(
    FilterResult filters,
    List<SetupCandidate> setups,
    SetupCandidate bestSetup,
    PlaybookPlanView plan,
    List<ChecklistItem> checklist,
    int checklistScore,
    String verdict,
    Instant evaluatedAt,
    boolean lateEntry
) {
    public static PlaybookEvaluationView from(PlaybookEvaluation evaluation, Instrument instrument) {
        if (evaluation == null) return null;
        return new PlaybookEvaluationView(
            evaluation.filters(),
            evaluation.setups(),
            evaluation.bestSetup(),
            PlaybookPlanView.from(evaluation.plan(), instrument),
            evaluation.checklist(),
            evaluation.checklistScore(),
            evaluation.verdict(),
            evaluation.evaluatedAt(),
            evaluation.lateEntry()
        );
    }
}
