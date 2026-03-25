package com.riskdesk.domain.trading.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event raised when a position is closed.
 */
public record PositionClosed(
        Long positionId,
        String instrument,
        BigDecimal exitPrice,
        BigDecimal realizedPnL,
        Instant timestamp
) {}
