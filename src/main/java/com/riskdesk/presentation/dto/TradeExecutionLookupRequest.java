package com.riskdesk.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record TradeExecutionLookupRequest(
    @NotEmpty List<@NotNull Long> mentorSignalReviewIds
) {
}
