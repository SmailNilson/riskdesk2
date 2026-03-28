package com.riskdesk.application.dto;

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
    MentorProposedTradePlan proposedTradePlan
) {
}
