package com.riskdesk.presentation.dto;

import java.time.Instant;

public record MentorSimilarAudit(
    Long auditId,
    Instant createdAt,
    String instrument,
    String timeframe,
    String action,
    String verdict,
    double similarity,
    String summary
) {
}
