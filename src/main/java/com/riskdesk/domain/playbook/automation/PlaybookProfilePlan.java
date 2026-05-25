package com.riskdesk.domain.playbook.automation;

import java.math.BigDecimal;

public record PlaybookProfilePlan(
    PlaybookExecutionProfile profile,
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit,
    BigDecimal targetR,
    boolean executable
) {
}
