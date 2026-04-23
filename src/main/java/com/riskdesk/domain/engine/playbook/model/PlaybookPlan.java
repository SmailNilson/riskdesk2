package com.riskdesk.domain.engine.playbook.model;

import java.math.BigDecimal;

public record PlaybookPlan(
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    double rrRatio,
    double riskPercent,
    String slRationale,
    String tp1Rationale
) {
    public PlaybookPlan withAdjustedSize(double newRiskPercent) {
        return new PlaybookPlan(entryPrice, stopLoss, takeProfit1, takeProfit2,
            rrRatio, newRiskPercent, slRationale, tp1Rationale);
    }
}
