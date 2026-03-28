package com.riskdesk.presentation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateTradeExecutionRequest(
    @NotNull Long mentorSignalReviewId,
    @NotBlank String brokerAccountId,
    @NotNull @Positive Integer quantity
) {
}
