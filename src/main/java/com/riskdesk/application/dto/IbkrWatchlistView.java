package com.riskdesk.application.dto;

import java.time.Instant;
import java.util.List;

public record IbkrWatchlistView(
    String id,
    String name,
    boolean readOnly,
    Instant importedAt,
    List<IbkrWatchlistInstrumentView> instruments
) {
}
