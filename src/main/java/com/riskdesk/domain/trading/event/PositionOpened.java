package com.riskdesk.domain.trading.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event raised when a new position is opened.
 */
public record PositionOpened(
        Long positionId,
        String instrument,
        String side,
        int quantity,
        BigDecimal entryPrice,
        Instant timestamp
) {}
