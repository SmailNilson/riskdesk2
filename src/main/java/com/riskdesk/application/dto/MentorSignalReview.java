package com.riskdesk.application.dto;

import com.riskdesk.domain.model.TradeSimulationStatus;

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
    String asset,
    String contractMonth,
    String contractSymbol,
    String timeframe,
    String action,
    String timestamp,
    String createdAt,
    TradeSimulationStatus simulationStatus,
    String activationTime,
    String resolutionTime,
    Double maxDrawdownPoints,
    MentorAnalyzeResponse analysis,
    String errorMessage
) {
}
