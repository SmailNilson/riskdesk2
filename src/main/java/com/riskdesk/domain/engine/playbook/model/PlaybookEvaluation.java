package com.riskdesk.domain.engine.playbook.model;

import java.time.Instant;
import java.util.List;

public record PlaybookEvaluation(
    FilterResult filters,
    List<SetupCandidate> setups,
    SetupCandidate bestSetup,
    PlaybookPlan plan,
    List<ChecklistItem> checklist,
    int checklistScore,
    String verdict,
    Instant evaluatedAt,
    boolean lateEntry
) {
    /**
     * Legacy 8-arg constructor — existing callers that don't yet supply the
     * {@code lateEntry} flag default to {@code false} (not late).
     * New code should use the canonical 9-arg constructor.
     */
    public PlaybookEvaluation(FilterResult filters, List<SetupCandidate> setups,
                              SetupCandidate bestSetup, PlaybookPlan plan,
                              List<ChecklistItem> checklist, int checklistScore,
                              String verdict, Instant evaluatedAt) {
        this(filters, setups, bestSetup, plan, checklist, checklistScore,
             verdict, evaluatedAt, false);
    }
}
