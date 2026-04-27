package com.riskdesk.domain.analysis.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One probabilistic outcome envisaged by the scoring engine. The output of the
 * scoring engine is a {@link DirectionalBias} plus a list of these scenarios,
 * each with its trigger condition and invalidation level.
 */
public record TradeScenario(
    String name,                   // "Continuation" / "Reversal" / "Range"
    double probability,            // 0..1, scenarios sum to 1.0
    Direction direction,
    BigDecimal entry,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    double rewardRiskRatio,
    String triggerCondition,
    String invalidation
) {
    public TradeScenario {
        Objects.requireNonNull(name);
        Objects.requireNonNull(direction);
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("probability out of [0,1]: " + probability);
        }
        // Range scenario allowed to skip prices
        if (direction != Direction.NEUTRAL) {
            Objects.requireNonNull(entry, "entry required for actionable scenario");
            Objects.requireNonNull(stopLoss, "stopLoss required for actionable scenario");
            Objects.requireNonNull(takeProfit1, "takeProfit1 required for actionable scenario");
        }
    }
}
