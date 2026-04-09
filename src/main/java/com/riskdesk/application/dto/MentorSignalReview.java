package com.riskdesk.application.dto;

import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.TradeSimulationStatus;
import com.riskdesk.domain.model.TrailingStopResult;

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
    String sourceType,
    String timestamp,
    String createdAt,
    String selectedTimezone,
    ExecutionEligibilityStatus executionEligibilityStatus,
    String executionEligibilityReason,
    TradeSimulationStatus simulationStatus,
    String activationTime,
    String resolutionTime,
    Double maxDrawdownPoints,
    TrailingStopResult trailingStopResult,
    Double trailingExitPrice,
    Double bestFavorablePrice,
    MentorAnalyzeResponse analysis,
    String errorMessage,
    Double triggerPrice
) {
}
