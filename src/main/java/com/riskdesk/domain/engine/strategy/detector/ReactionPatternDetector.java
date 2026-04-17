package com.riskdesk.domain.engine.strategy.detector;

import com.riskdesk.domain.engine.strategy.model.ReactionPattern;
import com.riskdesk.domain.model.Candle;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pure-domain candle pattern classifier. Examines the most recent closed candle
 * and classifies it into one of {@link ReactionPattern}.
 *
 * <h2>Classification rules</h2>
 *
 * <p>Let {@code body = |close - open|}, {@code range = high - low}. Reject
 * classification entirely (returns {@link ReactionPattern#NONE}) when the candle
 * has zero range.
 *
 * <ul>
 *   <li><b>REJECTION</b> — a dominant wick (opposite of the body direction) at
 *       least 60% of the range, body ≤ 30% of range. i.e. price tagged a level
 *       and got rejected.</li>
 *   <li><b>INDECISION</b> — body ≤ 15% of range (doji / spinner). Upper and
 *       lower wicks both material.</li>
 *   <li><b>ACCEPTANCE</b> — body ≥ 70% of range in the dominant direction; no
 *       large opposing wick. Shows commitment in the candle's direction.</li>
 *   <li><b>NONE</b> — anything else (mixed / ordinary candles).</li>
 * </ul>
 *
 * <p>Stateless and safe for concurrent use. A single {@link #classifyLatest(List)}
 * call is O(1) — it only reads the last candle. The list parameter is forward-
 * looking: callers can pass a longer series in case we later extend to 2-bar or
 * 3-bar patterns without changing the API surface.
 */
public final class ReactionPatternDetector {

    private static final BigDecimal MIN_REJECTION_WICK_FRACTION = new BigDecimal("0.60");
    private static final BigDecimal MAX_REJECTION_BODY_FRACTION = new BigDecimal("0.30");
    private static final BigDecimal MAX_INDECISION_BODY_FRACTION = new BigDecimal("0.15");
    private static final BigDecimal MIN_ACCEPTANCE_BODY_FRACTION = new BigDecimal("0.70");

    private ReactionPatternDetector() {
        throw new AssertionError("no instances");
    }

    public static ReactionPattern classifyLatest(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return ReactionPattern.NONE;
        Candle c = candles.get(candles.size() - 1);
        if (c.getHigh() == null || c.getLow() == null
            || c.getOpen() == null || c.getClose() == null) {
            return ReactionPattern.NONE;
        }

        BigDecimal range = c.range();
        if (range.signum() <= 0) return ReactionPattern.NONE;

        BigDecimal body = c.body();
        BigDecimal bodyPct = body.divide(range, 4, java.math.RoundingMode.HALF_UP);
        BigDecimal upperWick = c.upperWick();
        BigDecimal lowerWick = c.lowerWick();
        BigDecimal upperWickPct = upperWick.divide(range, 4, java.math.RoundingMode.HALF_UP);
        BigDecimal lowerWickPct = lowerWick.divide(range, 4, java.math.RoundingMode.HALF_UP);

        // Rejection candle — dominant wick OPPOSITE to body direction.
        if (bodyPct.compareTo(MAX_REJECTION_BODY_FRACTION) <= 0) {
            // bullish candle with dominant lower wick = rejection of lower prices
            if (c.isBullish() && lowerWickPct.compareTo(MIN_REJECTION_WICK_FRACTION) >= 0) {
                return ReactionPattern.REJECTION;
            }
            // bearish candle with dominant upper wick = rejection of higher prices
            if (c.isBearish() && upperWickPct.compareTo(MIN_REJECTION_WICK_FRACTION) >= 0) {
                return ReactionPattern.REJECTION;
            }
            // pinbar with tiny body either direction — also rejection
            if (upperWickPct.compareTo(MIN_REJECTION_WICK_FRACTION) >= 0
                || lowerWickPct.compareTo(MIN_REJECTION_WICK_FRACTION) >= 0) {
                return ReactionPattern.REJECTION;
            }
        }

        // Doji / indecision — very small body, no wick dominates.
        if (bodyPct.compareTo(MAX_INDECISION_BODY_FRACTION) <= 0) {
            return ReactionPattern.INDECISION;
        }

        // Acceptance — body dominates, committed move.
        if (bodyPct.compareTo(MIN_ACCEPTANCE_BODY_FRACTION) >= 0) {
            return ReactionPattern.ACCEPTANCE;
        }

        return ReactionPattern.NONE;
    }
}
