package com.riskdesk.presentation.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record LivePriceView(
    String instrument,
    BigDecimal price,
    Instant timestamp,
    String source,
    String asset,
    String contractMonth,
    String contractSymbol,
    String selectionReason
) {
}
