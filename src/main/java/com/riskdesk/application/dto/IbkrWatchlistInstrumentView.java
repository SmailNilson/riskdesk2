package com.riskdesk.application.dto;

public record IbkrWatchlistInstrumentView(
    long conid,
    String symbol,
    String localSymbol,
    String name,
    String assetClass,
    String instrumentCode
) {
}
