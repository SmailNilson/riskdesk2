package com.riskdesk.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record MentorAnalyzeResponse(
    Long auditId,
    String model,
    JsonNode payload,
    MentorStructuredResponse analysis,
    String rawResponse,
    java.util.List<MentorSimilarAudit> similarAudits
) {
}
