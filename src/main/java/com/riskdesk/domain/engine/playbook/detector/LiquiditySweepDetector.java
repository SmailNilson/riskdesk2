package com.riskdesk.domain.engine.playbook.detector;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SetupType;
import com.riskdesk.domain.engine.playbook.model.SmcEqualLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Setup B: Detects price sweeping an EQL/EQH liquidity pool.
 * <p>
 * A sweep is confirmed when price crosses beyond the equal level then
 * reclaims (closes back on the other side), indicating a stop hunt.
 * Without real-time candles, we detect proximity to EQL/EQH as "approaching".
 */
public class LiquiditySweepDetector {

    public List<SetupCandidate> detect(PlaybookInput input, Direction direction) {
        List<SetupCandidate> candidates = new ArrayList<>();
        BigDecimal price = input.lastPrice();
        BigDecimal atr = input.atr();
        if (price == null || atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) return candidates;

        double approachThreshold = atr.doubleValue() * 1.5;

        // LONG -> look for EQL sweep (price hunts stops below equal lows)
        // SHORT -> look for EQH sweep (price hunts stops above equal highs)
        List<SmcEqualLevel> pools = direction == Direction.LONG
            ? input.equalLows() : input.equalHighs();

        for (SmcEqualLevel pool : pools) {
            BigDecimal poolPrice = pool.price();
            double distance = price.subtract(poolPrice).abs().doubleValue();

            if (distance > approachThreshold) continue;

            // Determine sweep status from price position
            boolean priceBelow = price.compareTo(poolPrice) < 0;
            boolean priceAbove = price.compareTo(poolPrice) > 0;
            boolean swept = (direction == Direction.LONG && priceBelow)
                         || (direction == Direction.SHORT && priceAbove);

            BigDecimal halfAtr = atr.multiply(BigDecimal.valueOf(0.5));
            BigDecimal zoneHigh = poolPrice.add(halfAtr);
            BigDecimal zoneLow = poolPrice.subtract(halfAtr);
            BigDecimal mid = poolPrice;

            candidates.add(new SetupCandidate(
                SetupType.LIQUIDITY_SWEEP,
                (direction == Direction.LONG ? "EQL" : "EQH")
                    + " Sweep " + pool.price() + " x" + pool.touchCount(),
                zoneHigh, zoneLow, mid,
                distance, swept,
                swept, // if price crossed and is near = reaction visible
                ZoneRetestDetector.isOrderFlowConfirming(input),
                0.0, 0
            ));
        }

        return candidates;
    }
}
