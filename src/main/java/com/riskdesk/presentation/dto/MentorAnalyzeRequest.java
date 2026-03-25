package com.riskdesk.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record MentorAnalyzeRequest(
    @NotNull JsonNode payload
) {
}
