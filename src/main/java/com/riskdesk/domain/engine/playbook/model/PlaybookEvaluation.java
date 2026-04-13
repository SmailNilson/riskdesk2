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
    Instant evaluatedAt
) {}
