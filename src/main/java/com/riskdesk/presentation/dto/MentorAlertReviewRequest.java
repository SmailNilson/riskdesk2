package com.riskdesk.presentation.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record MentorAlertReviewRequest(
    @NotBlank String severity,
    @NotBlank String category,
    @NotBlank String message,
    String instrument,
    @NotBlank String timestamp,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit
) {
}
