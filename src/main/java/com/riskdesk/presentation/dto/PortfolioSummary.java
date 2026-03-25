package com.riskdesk.presentation.dto;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioSummary(
        BigDecimal totalUnrealizedPnL,
        BigDecimal todayRealizedPnL,
        BigDecimal totalPnL,
        long openPositionCount,
        BigDecimal totalExposure,
        BigDecimal marginUsedPct,
        List<PositionView> openPositions
) {}
