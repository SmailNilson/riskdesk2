package com.riskdesk.application.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record MentorAnalyzeResponse(
    Long auditId,
    String model,
    JsonNode payload,
    MentorStructuredResponse analysis,
    String rawResponse,
    List<MentorSimilarAudit> similarAudits
) {
}
