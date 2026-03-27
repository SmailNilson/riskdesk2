package com.riskdesk.application.dto;

import com.riskdesk.domain.model.TradeSimulationStatus;

public record MentorManualReview(
    Long auditId,
    String sourceType,
    String createdAt,
    String instrument,
    String asset,
    String contractMonth,
    String contractSymbol,
    String timeframe,
    String action,
    String model,
    String verdict,
    boolean success,
    String errorMessage,
    MentorAnalyzeResponse response,
    TradeSimulationStatus simulationStatus,
    String activationTime,
    String resolutionTime,
    Double maxDrawdownPoints
) {
}
