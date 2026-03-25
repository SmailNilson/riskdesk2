package com.riskdesk.domain.marketdata.event;

import java.time.Instant;

/**
 * Domain event raised when a candle (bar) closes.
 */
public record CandleClosed(
        String instrument,
        String timeframe,
        Instant timestamp
) {}
