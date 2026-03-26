package com.riskdesk.application.dto;

public record MentorSignalReview(
    Long id,
    String alertKey,
    int revision,
    String triggerType,
    String status,
    String severity,
    String category,
    String message,
    String instrument,
    String timeframe,
    String action,
    String timestamp,
    String createdAt,
    MentorAnalyzeResponse analysis,
    String errorMessage
) {
}
