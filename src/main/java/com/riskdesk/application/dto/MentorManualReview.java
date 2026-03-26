package com.riskdesk.application.dto;

public record MentorManualReview(
    Long auditId,
    String sourceType,
    String createdAt,
    String instrument,
    String timeframe,
    String action,
    String model,
    String verdict,
    boolean success,
    String errorMessage,
    MentorAnalyzeResponse response
) {
}
