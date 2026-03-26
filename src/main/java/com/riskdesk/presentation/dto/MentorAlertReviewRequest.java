package com.riskdesk.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record MentorAlertReviewRequest(
    @NotBlank String severity,
    @NotBlank String category,
    @NotBlank String message,
    String instrument,
    @NotBlank String timestamp
) {
}
