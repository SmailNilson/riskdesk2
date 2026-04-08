package com.riskdesk.application.dto;

import java.time.Instant;

public record MentorSimilarAudit(
    Long auditId,
    Instant createdAt,
    String instrument,
    String timeframe,
    String action,
    String verdict,
    double similarity,
    String summary,
    String simulationStatus
) {
    /** Legacy constructor for callers that don't provide simulationStatus. */
    public MentorSimilarAudit(Long auditId, Instant createdAt, String instrument,
                              String timeframe, String action, String verdict,
                              double similarity, String summary) {
        this(auditId, createdAt, instrument, timeframe, action, verdict,
             similarity, summary, null);
    }
}
