package com.riskdesk.presentation.dto;

import jakarta.validation.constraints.NotBlank;

public record HistoricalTradeImportFileRequest(
    @NotBlank(message = "filePath is required")
    String filePath
) {
}
