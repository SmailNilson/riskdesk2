package com.riskdesk.domain.engine.playbook.detector;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SetupType;
import com.riskdesk.domain.engine.playbook.model.SmcFvg;
import com.riskdesk.domain.engine.playbook.model.SmcOrderBlock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Setup A: Detects price approaching or sitting inside a support/resistance zone
 * aligned with the swing bias (Order Blocks, Breakers, or aligned FVGs).
 */
public class ZoneRetestDetector {

    private static final double PROXIMITY_THRESHOLD = 1.5;

    public List<SetupCandidate> detect(PlaybookInput input, Direction direction) {
        List<SetupCandidate> candidates = new ArrayList<>();
        BigDecimal price = input.lastPrice();
        if (price == null) return candidates;

        // 1. Active Order Blocks aligned with bias
        for (SmcOrderBlock ob : input.activeOrderBlocks()) {
            if (!isAligned(ob.type(), direction)) continue;
            addIfProximate(candidates, price, ob.high(), ob.low(), ob.mid(),
                "OB " + ob.type() + " " + ob.low() + "-" + ob.high(),
                input);
        }

        // 2. Breaker blocks (inverted polarity — bullish breaker = came from bearish OB)
        for (SmcOrderBlock brk : input.breakerOrderBlocks()) {
            String breakerBias = breakerAlignedBias(brk);
            if (!breakerBias.equalsIgnoreCase(direction.name())) continue;
            addIfProximate(candidates, price, brk.high(), brk.low(), brk.mid(),
                "Breaker " + brk.low() + "-" + brk.high(),
                input);
        }

        // 3. Aligned FVGs (bullish FVG = support for LONG, bearish FVG = resistance for SHORT)
        for (SmcFvg fvg : input.activeFairValueGaps()) {
            boolean aligned = (direction == Direction.LONG && "BULLISH".equalsIgnoreCase(fvg.bias()))
                           || (direction == Direction.SHORT && "BEARISH".equalsIgnoreCase(fvg.bias()));
            if (!aligned) continue;
            BigDecimal mid = fvg.top().add(fvg.bottom()).divide(BigDecimal.TWO, 6, RoundingMode.HALF_UP);
            addIfProximate(candidates, price, fvg.top(), fvg.bottom(), mid,
                "FVG " + fvg.bias() + " " + fvg.bottom() + "-" + fvg.top(),
                input);
        }

        return candidates;
    }

    private void addIfProximate(List<SetupCandidate> list, BigDecimal price,
                                BigDecimal high, BigDecimal low, BigDecimal mid,
                                String zoneName, PlaybookInput input) {
        if (high == null || low == null || mid == null) return;
        BigDecimal zoneSize = high.subtract(low);
        if (zoneSize.compareTo(BigDecimal.ZERO) <= 0) return;

        BigDecimal distance = price.subtract(mid).abs();
        double proximity = distance.doubleValue() / zoneSize.doubleValue();

        if (proximity <= PROXIMITY_THRESHOLD) {
            boolean inZone = price.compareTo(low) >= 0 && price.compareTo(high) <= 0;
            boolean ofConfirms = isOrderFlowConfirming(input);

            list.add(new SetupCandidate(
                SetupType.ZONE_RETEST, zoneName, high, low, mid,
                distance.doubleValue(), inZone, false, ofConfirms, 0.0, 0
            ));
        }
    }

    private boolean isAligned(String obType, Direction direction) {
        return (direction == Direction.LONG && "BULLISH".equalsIgnoreCase(obType))
            || (direction == Direction.SHORT && "BEARISH".equalsIgnoreCase(obType));
    }

    private String breakerAlignedBias(SmcOrderBlock brk) {
        // A breaker's aligned bias is the OPPOSITE of its original type:
        // bearish OB that broke -> bullish breaker (support)
        if (brk.originalType() != null) {
            return "BEARISH".equalsIgnoreCase(brk.originalType()) ? "LONG" : "SHORT";
        }
        // Fallback: use the current type (already inverted in most implementations)
        return "BULLISH".equalsIgnoreCase(brk.type()) ? "LONG" : "SHORT";
    }

    static boolean isOrderFlowConfirming(PlaybookInput input) {
        if (input.deltaFlowBias() == null) return false;
        return !"NEUTRAL".equalsIgnoreCase(input.deltaFlowBias())
            && !input.deltaFlowBias().isBlank();
    }
}
