package com.riskdesk.domain.forwardtest.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One leg of a forward-test position (scaling-in pattern: Leg 1 = standard entry, Leg 2 = deep entry).
 *
 * @param targetPrice   the limit price the evaluator watches for fill
 * @param fillPrice     actual fill price including slippage (null until filled)
 * @param fillTime      timestamp of fill candle (null until filled)
 * @param quantity      number of contracts for this leg
 * @param slippageTicks applied slippage in ticks (adverse direction)
 */
public record PositionLeg(
        BigDecimal targetPrice,
        BigDecimal fillPrice,
        Instant fillTime,
        int quantity,
        int slippageTicks
) {

    /** Creates an unfilled leg awaiting execution. */
    public static PositionLeg pending(BigDecimal targetPrice, int quantity) {
        return new PositionLeg(targetPrice, null, null, quantity, 0);
    }

    /** Returns a filled copy of this leg with slippage applied. */
    public PositionLeg fill(BigDecimal price, Instant time, int slippage) {
        return new PositionLeg(targetPrice, price, time, quantity, slippage);
    }

    public boolean isFilled() {
        return fillPrice != null;
    }
}
