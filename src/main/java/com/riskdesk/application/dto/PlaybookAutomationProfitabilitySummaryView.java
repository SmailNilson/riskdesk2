package com.riskdesk.application.dto;

import java.math.BigDecimal;

public record PlaybookAutomationProfitabilitySummaryView(
    int totalDecisions,
    int paperCount,
    int liveCount,
    int wins,
    int losses,
    int missed,
    double winRate,
    BigDecimal totalPnl,
    BigDecimal averagePnl,
    BigDecimal profitFactor,
    BigDecimal avgMaxDrawdownPoints
) {
}
