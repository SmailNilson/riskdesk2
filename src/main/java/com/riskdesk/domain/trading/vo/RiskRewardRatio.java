package com.riskdesk.domain.trading.vo;

import com.riskdesk.domain.shared.vo.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Value object representing a risk/reward ratio.
 * Calculated as reward / risk, with scale 2.
 */
public final class RiskRewardRatio {

    private final BigDecimal value;

    private RiskRewardRatio(BigDecimal value) {
        this.value = value;
    }

    /**
     * Calculates the risk/reward ratio as reward divided by risk.
     *
     * @return the ratio, or null if either input is null or zero
     */
    public static RiskRewardRatio calculate(Money risk, Money reward) {
        if (risk == null || reward == null) {
            return null;
        }
        if (risk.isZero() || reward.isZero()) {
            return null;
        }
        BigDecimal ratio = reward.amount().divide(risk.amount(), 2, RoundingMode.HALF_UP);
        return new RiskRewardRatio(ratio);
    }

    public BigDecimal value() {
        return value;
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }
}
