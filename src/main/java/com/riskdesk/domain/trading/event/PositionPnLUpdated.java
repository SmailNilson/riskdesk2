package com.riskdesk.domain.trading.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event raised when a position's unrealized P&L is updated.
 */
public record PositionPnLUpdated(
        Long positionId,
        String instrument,
        BigDecimal marketPrice,
        BigDecimal unrealizedPnL,
        Instant timestamp
) {}
