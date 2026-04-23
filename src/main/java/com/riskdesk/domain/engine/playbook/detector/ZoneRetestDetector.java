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
 *
 * <p><b>PR-10 · Proximity is scaled by ATR, not by the zone's own width.</b>
 * The old formula divided distance by zone size, which produced the wrong
 * behaviour at both extremes: a 1-pt FVG was declared "far" the moment price
 * drifted 1.5 pts away (noise-level on a $MNQ tick), while a 50-pt Order Block
 * was declared "near" even when price was 75 pts away. ATR is the instrument's
 * natural reach; scaling proximity by ATR keeps the gate consistent across
 * zone sizes and across volatility regimes.
 *
 * <p>Distance semantics: the reported {@code distanceFromPrice} is now the
 * <em>boundary</em> distance — zero when price is inside the zone, otherwise
 * the gap to the nearest edge. That matches trader intuition ("how far am I
 * from this zone?") and stops the earlier mid-distance metric from
 * mis-ranking narrow zones that price has already reached.
 */
public class ZoneRetestDetector {

    /**
     * A zone counts as "in proximity" when price sits within
     * {@code PROXIMITY_ATR_MULTIPLIER × ATR} of its nearest boundary. 1.5 ATR
     * is wide enough to catch a fresh retest setup forming, tight enough to
     * exclude zones that are still a full swing away.
     */
    private static final double PROXIMITY_ATR_MULTIPLIER = 1.5;

    public List<SetupCandidate> detect(PlaybookInput input, Direction direction) {
        List<SetupCandidate> candidates = new ArrayList<>();
        BigDecimal price = input.lastPrice();
        BigDecimal atr = input.atr();
        // Fail-open: without price or a positive ATR we cannot scale proximity.
        if (price == null || atr == null || atr.signum() <= 0) return candidates;

        // 1. Active Order Blocks aligned with bias
        for (SmcOrderBlock ob : input.activeOrderBlocks()) {
            if (!isAligned(ob.type(), direction)) continue;
            addIfProximate(candidates, price, atr, ob.high(), ob.low(), ob.mid(),
                "OB " + ob.type() + " " + ob.low() + "-" + ob.high(),
                input);
        }

        // 2. Breaker blocks (inverted polarity — bullish breaker = came from bearish OB)
        for (SmcOrderBlock brk : input.breakerOrderBlocks()) {
            String breakerBias = breakerAlignedBias(brk);
            if (!breakerBias.equalsIgnoreCase(direction.name())) continue;
            addIfProximate(candidates, price, atr, brk.high(), brk.low(), brk.mid(),
                "Breaker " + brk.low() + "-" + brk.high(),
                input);
        }

        // 3. Aligned FVGs (bullish FVG = support for LONG, bearish FVG = resistance for SHORT)
        for (SmcFvg fvg : input.activeFairValueGaps()) {
            boolean aligned = (direction == Direction.LONG && "BULLISH".equalsIgnoreCase(fvg.bias()))
                           || (direction == Direction.SHORT && "BEARISH".equalsIgnoreCase(fvg.bias()));
            if (!aligned) continue;
            BigDecimal mid = fvg.top().add(fvg.bottom()).divide(BigDecimal.TWO, 6, RoundingMode.HALF_UP);
            addIfProximate(candidates, price, atr, fvg.top(), fvg.bottom(), mid,
                "FVG " + fvg.bias() + " " + fvg.bottom() + "-" + fvg.top(),
                input);
        }

        return candidates;
    }

    private void addIfProximate(List<SetupCandidate> list, BigDecimal price, BigDecimal atr,
                                BigDecimal high, BigDecimal low, BigDecimal mid,
                                String zoneName, PlaybookInput input) {
        if (high == null || low == null || mid == null) return;
        // Guard against degenerate zones (zero or inverted width).
        if (high.compareTo(low) <= 0) return;

        // Boundary distance: 0 when inside, otherwise distance to the nearest edge.
        boolean inZone = price.compareTo(low) >= 0 && price.compareTo(high) <= 0;
        BigDecimal boundaryDistance;
        if (inZone) {
            boundaryDistance = BigDecimal.ZERO;
        } else if (price.compareTo(high) > 0) {
            boundaryDistance = price.subtract(high);
        } else {
            boundaryDistance = low.subtract(price);
        }

        BigDecimal threshold = atr.multiply(BigDecimal.valueOf(PROXIMITY_ATR_MULTIPLIER));
        if (boundaryDistance.compareTo(threshold) > 0) return;

        boolean ofConfirms = isOrderFlowConfirming(input);
        list.add(new SetupCandidate(
            SetupType.ZONE_RETEST, zoneName, high, low, mid,
            boundaryDistance.doubleValue(), inZone, false, ofConfirms, 0.0, 0
        ));
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
