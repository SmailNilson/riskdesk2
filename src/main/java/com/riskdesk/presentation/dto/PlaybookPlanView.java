package com.riskdesk.presentation.dto;

import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;

/**
 * Presentation view of {@link PlaybookPlan} that adds the contract spec
 * (multiplier / tick size / tick value) so the UI can render dollar-denominated
 * risk and notional without duplicating the per-instrument table on the client.
 */
public record PlaybookPlanView(
    BigDecimal entryPrice,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    double rrRatio,
    double riskPercent,
    String slRationale,
    String tp1Rationale,
    BigDecimal contractMultiplier,
    BigDecimal tickSize,
    BigDecimal tickValue
) {
    public static PlaybookPlanView from(PlaybookPlan plan, Instrument instrument) {
        if (plan == null) return null;
        return new PlaybookPlanView(
            plan.entryPrice(),
            plan.stopLoss(),
            plan.takeProfit1(),
            plan.takeProfit2(),
            plan.rrRatio(),
            plan.riskPercent(),
            plan.slRationale(),
            plan.tp1Rationale(),
            instrument.getContractMultiplier(),
            instrument.getTickSize(),
            instrument.getTickValue()
        );
    }
}
