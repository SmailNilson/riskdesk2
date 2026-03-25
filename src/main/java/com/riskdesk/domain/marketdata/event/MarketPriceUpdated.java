package com.riskdesk.domain.marketdata.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event raised when a market price is updated.
 */
public record MarketPriceUpdated(
        String instrument,
        BigDecimal price,
        Instant timestamp
) {}
