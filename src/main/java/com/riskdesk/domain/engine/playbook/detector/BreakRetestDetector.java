package com.riskdesk.domain.engine.playbook.detector;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookInput;
import com.riskdesk.domain.engine.playbook.model.SetupCandidate;
import com.riskdesk.domain.engine.playbook.model.SetupType;
import com.riskdesk.domain.engine.playbook.model.SmcBreak;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Setup C: Detects a BOS (Break of Structure) followed by a retest of the
 * broken level. The price must be close to the broken level and the break
 * must be aligned with the swing bias.
 */
public class BreakRetestDetector {

    public List<SetupCandidate> detect(PlaybookInput input, Direction direction) {
        List<SetupCandidate> candidates = new ArrayList<>();
        BigDecimal price = input.lastPrice();
        BigDecimal atr = input.atr();
        if (price == null || atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) return candidates;

        for (SmcBreak brk : input.recentBreaks()) {
            // Only BOS (not CHoCH -- CHoCH is a reversal, not continuation)
            if (!"BOS".equalsIgnoreCase(brk.type())) continue;

            // Must be aligned with the trade direction
            boolean aligned = (direction == Direction.LONG && "BULLISH".equalsIgnoreCase(brk.trend()))
                           || (direction == Direction.SHORT && "BEARISH".equalsIgnoreCase(brk.trend()));
            if (!aligned) continue;

            // Skip FAKE? breaks (low confidence)
            if (brk.breakConfidenceScore() != null && brk.breakConfidenceScore() < 0.60) continue;

            // Check if price is retesting the broken level
            BigDecimal level = brk.level();
            if (level == null) continue;
            BigDecimal distance = price.subtract(level).abs();
            boolean retest = distance.doubleValue() <= atr.doubleValue() * 0.5;

            if (retest) {
                BigDecimal margin = atr.multiply(BigDecimal.valueOf(0.3));
                BigDecimal zoneHigh = level.add(margin);
                BigDecimal zoneLow = level.subtract(margin);

                candidates.add(new SetupCandidate(
                    SetupType.BREAK_RETEST,
                    "BOS Retest @ " + level,
                    zoneHigh, zoneLow, level,
                    distance.doubleValue(), true,
                    false,
                    ZoneRetestDetector.isOrderFlowConfirming(input),
                    0.0, 0
                ));
            }
        }

        return candidates;
    }
}
