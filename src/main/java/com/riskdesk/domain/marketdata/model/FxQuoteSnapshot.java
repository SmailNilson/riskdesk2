package com.riskdesk.domain.marketdata.model;

import java.math.BigDecimal;
import java.time.Instant;

public record FxQuoteSnapshot(
    FxPair pair,
    BigDecimal bid,
    BigDecimal ask,
    BigDecimal last,
    BigDecimal close,
    Instant timestamp,
    String source
) {
}
