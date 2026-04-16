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
 * Setup C: Detects a structure break (BOS or CHoCH) followed by a retest of the
 * broken level. Price must sit close to the broken level and the break must be
 * aligned with the trade direction.
 *
 * <p><b>BOS vs CHoCH retests — both are legitimate.</b> A BOS (Break of
 * Structure) retest trades a continuation: the trend is pushing further and the
 * broken level becomes fresh support/resistance. A CHoCH (Change of Character)
 * retest trades the first pullback after a trend flip — often the cleanest R:R
 * entry into a newly confirmed direction.
 *
 * <p>What keeps counter-trend setups out is the <em>alignment</em> check
 * ({@link Direction} vs {@code brk.trend()}), not the structure type. A bearish
 * CHoCH while we are LONG-biased fails alignment and is skipped; a bullish
 * CHoCH while we are LONG-biased is exactly the fresh-trend entry we want.
 */
public class BreakRetestDetector {

    /** Confidence floor below which a break is treated as noise and skipped. */
    private static final double MIN_CONFIDENCE = 0.60;
    /** Retest proximity: price must sit within this fraction of ATR of the level. */
    private static final double RETEST_PROXIMITY_ATR = 0.5;
    /** Zone half-width around the broken level, as a fraction of ATR. */
    private static final double ZONE_MARGIN_ATR = 0.3;

    public List<SetupCandidate> detect(PlaybookInput input, Direction direction) {
        List<SetupCandidate> candidates = new ArrayList<>();
        BigDecimal price = input.lastPrice();
        BigDecimal atr = input.atr();
        if (price == null || atr == null || atr.compareTo(BigDecimal.ZERO) <= 0) return candidates;

        for (SmcBreak brk : input.recentBreaks()) {
            // Accept BOS (continuation) and CHoCH (reversal) — reject anything else.
            if (!isStructureBreak(brk)) continue;

            // Must be aligned with the trade direction (keeps counter-trend breaks out).
            boolean aligned = (direction == Direction.LONG && "BULLISH".equalsIgnoreCase(brk.trend()))
                           || (direction == Direction.SHORT && "BEARISH".equalsIgnoreCase(brk.trend()));
            if (!aligned) continue;

            // Skip low-confidence / "FAKE?" breaks.
            if (brk.breakConfidenceScore() != null && brk.breakConfidenceScore() < MIN_CONFIDENCE) continue;

            // Price must be retesting the broken level.
            BigDecimal level = brk.level();
            if (level == null) continue;
            BigDecimal distance = price.subtract(level).abs();
            boolean retest = distance.doubleValue() <= atr.doubleValue() * RETEST_PROXIMITY_ATR;
            if (!retest) continue;

            BigDecimal margin = atr.multiply(BigDecimal.valueOf(ZONE_MARGIN_ATR));
            BigDecimal zoneHigh = level.add(margin);
            BigDecimal zoneLow = level.subtract(margin);
            String label = normalizeBreakLabel(brk.type());

            candidates.add(new SetupCandidate(
                SetupType.BREAK_RETEST,
                label + " Retest @ " + level,
                zoneHigh, zoneLow, level,
                distance.doubleValue(), true,
                false,
                ZoneRetestDetector.isOrderFlowConfirming(input),
                0.0, 0
            ));
        }

        return candidates;
    }

    /** BOS (continuation) and CHoCH (trend change) are both retest-worthy. */
    private static boolean isStructureBreak(SmcBreak brk) {
        String type = brk.type();
        if (type == null) return false;
        return "BOS".equalsIgnoreCase(type) || "CHOCH".equalsIgnoreCase(type);
    }

    /** Keep the human-readable casing stable in zone names / logs. */
    private static String normalizeBreakLabel(String type) {
        return "CHOCH".equalsIgnoreCase(type) ? "CHoCH" : "BOS";
    }
}
