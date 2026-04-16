package com.riskdesk.domain.engine.playbook.model;

import java.math.BigDecimal;

/**
 * Mechanical trade plan produced by the playbook calculator, with Entry / SL /
 * TPs / R:R / risk sizing.
 *
 * <h3>Risk-sizing units</h3>
 *
 * <p>{@code riskPercent} is a <b>risk fraction</b>, not a display percent.
 * {@code 0.01} means "1% of account equity at risk", {@code 0.005} means 0.5%.
 * See {@link RiskFraction} for the canonical contract and for the display
 * helper ({@link RiskFraction#toPercent(double)}) used in logs / narration.
 *
 * <p>The compact constructor rejects values outside {@code [0, RiskFraction.MAX_FRACTION]}
 * so that dimensionally-wrong values (e.g. {@code 1.0} for "1%") are caught at
 * construction time instead of bleeding into execution size.
 */
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
    public PlaybookPlan {
        RiskFraction.requireValid(riskPercent);
    }

    public PlaybookPlan withAdjustedSize(double newRiskPercent) {
        return new PlaybookPlan(entryPrice, stopLoss, takeProfit1, takeProfit2,
            rrRatio, newRiskPercent, slRationale, tp1Rationale);
    }

    /**
     * {@link #riskPercent} expressed as a display percent, e.g. {@code 0.01 → 1.0}.
     * Prefer this method over manual {@code riskPercent() * 100} at log / UI /
     * narration boundaries so the unit intent is explicit in the caller.
     */
    public double riskPercentDisplay() {
        return RiskFraction.toPercent(riskPercent);
    }
}
