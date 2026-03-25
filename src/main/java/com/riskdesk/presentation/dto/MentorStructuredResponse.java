package com.riskdesk.presentation.dto;

import java.util.List;

public record MentorStructuredResponse(
    String technicalQuickAnalysis,
    List<String> strengths,
    List<String> errors,
    String verdict,
    String improvementTip,
    MentorProposedTradePlan proposedTradePlan
) {
}
