package com.riskdesk.domain.engine.strategy.model;

import com.riskdesk.domain.engine.playbook.model.Direction;

import java.math.BigDecimal;

/**
 * Entry/SL/TP triplet produced by a {@link com.riskdesk.domain.engine.strategy.playbook.Playbook}.
 *
 * <p>Reuses {@link Direction} from the legacy playbook package — it's a pure enum
 * with no Spring dependency, so keeping one shared type avoids duplicate conversions
 * at the application boundary.
 */
public record MechanicalPlan(
    Direction direction,
    BigDecimal entry,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    double rrRatio
) {
    public MechanicalPlan {
        if (direction == null) throw new IllegalArgumentException("direction required");
        if (entry == null || stopLoss == null || takeProfit1 == null) {
            throw new IllegalArgumentException("entry / stopLoss / takeProfit1 must all be non-null");
        }
        if (rrRatio < 0.0) throw new IllegalArgumentException("rrRatio must be >= 0");
    }
}
