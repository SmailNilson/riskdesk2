package com.riskdesk.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riskdesk.domain.model.ExecutionEligibilityStatus;

import java.util.List;

public record MentorStructuredResponse(
    String technicalQuickAnalysis,
    List<String> strengths,
    List<String> errors,
    String verdict,
    ExecutionEligibilityStatus executionEligibilityStatus,
    String executionEligibilityReason,
    String improvementTip,
    MentorProposedTradePlan proposedTradePlan,
    /**
     * Non-null when the evaluated direction was rejected with enough
     * contradictions to warrant a second look at the inverse side. Populated
     * by {@code InverseBiasAnalyzer} in the application layer. Advisory only —
     * the system never auto-arms the inverse.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    MentorInverseBiasHint inverseBiasHint
) {
    /** Legacy constructor — keeps pre-inverse-hint callers source-compatible. */
    public MentorStructuredResponse(
        String technicalQuickAnalysis,
        List<String> strengths,
        List<String> errors,
        String verdict,
        ExecutionEligibilityStatus executionEligibilityStatus,
        String executionEligibilityReason,
        String improvementTip,
        MentorProposedTradePlan proposedTradePlan
    ) {
        this(technicalQuickAnalysis, strengths, errors, verdict,
            executionEligibilityStatus, executionEligibilityReason,
            improvementTip, proposedTradePlan, null);
    }
}
