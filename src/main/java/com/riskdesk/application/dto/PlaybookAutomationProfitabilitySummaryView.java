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
    BigDecimal avgMaxDrawdownPoints,
    /** Total P&L valued at the realistic (live) fill — late-entry chase included. */
    BigDecimal realisticTotalPnl,
    /** Profit factor on realistic P&L. */
    BigDecimal realisticProfitFactor
) {
}
