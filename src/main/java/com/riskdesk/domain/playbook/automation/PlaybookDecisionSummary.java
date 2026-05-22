package com.riskdesk.domain.playbook.automation;

import java.math.BigDecimal;

public record PlaybookDecisionSummary(
    int total,
    int resolved,
    int wins,
    int losses,
    int missed,
    double winRate,
    BigDecimal avgMaxDrawdownPoints
) {
    public static PlaybookDecisionSummary empty() {
        return new PlaybookDecisionSummary(0, 0, 0, 0, 0, 0.0, BigDecimal.ZERO);
    }
}
