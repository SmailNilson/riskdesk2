package com.riskdesk.presentation.dto;

public record HistoricalTradeImportResponse(
    String instrument,
    int imported,
    int skipped,
    int failed,
    String storage,
    String embeddingModel,
    String source
) {
}
