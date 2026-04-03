package com.riskdesk.domain.behaviouralert.rule;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertCategory;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext.SrLevel;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires when price is within 0.15% of a key level AND CMF confirms direction:
 * <ul>
 *   <li>Near support + CMF &gt; 0 → bullish confirmation</li>
 *   <li>Near resistance + CMF &lt; 0 → bearish confirmation</li>
 * </ul>
 * Candidate levels: EMA50, EMA200, and all S/R levels from context.
 * Candle-close guarded per level.
 */
public class CmfPriceConfirmationRule implements BehaviourAlertRule {

    static final BigDecimal PROXIMITY_THRESHOLD = new BigDecimal("0.0015");

    private static final Set<String> SUPPORT_TYPES = Set.of("EQL", "STRONG_LOW", "WEAK_LOW");
    private static final Set<String> RESISTANCE_TYPES = Set.of("EQH", "STRONG_HIGH", "WEAK_HIGH");

    private final ConcurrentHashMap<String, Instant> lastFiredCandle = new ConcurrentHashMap<>();

    @Override
    public List<BehaviourAlertSignal> evaluate(BehaviourAlertContext context) {
        if (context.lastPrice() == null || context.cmf() == null
                || context.cmf().compareTo(BigDecimal.ZERO) == 0) {
            return Collections.emptyList();
        }

        Instant candle = context.lastCandleTimestamp();
        if (candle == null) {
            return Collections.emptyList();
        }

        List<CandidateLevel> candidates = buildCandidates(context);
        List<BehaviourAlertSignal> signals = new ArrayList<>();

        for (CandidateLevel candidate : candidates) {
            if (candidate.price().compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal pct = context.lastPrice().subtract(candidate.price()).abs()
                    .divide(candidate.price(), 6, RoundingMode.HALF_UP);

            if (pct.compareTo(PROXIMITY_THRESHOLD) >= 0) continue;

            boolean isSupport = isSupport(candidate, context.lastPrice());
            boolean cmfPositive = context.cmf().compareTo(BigDecimal.ZERO) > 0;

            String confirmation;
            if (isSupport && cmfPositive) {
                confirmation = "Bullish";
            } else if (!isSupport && !cmfPositive) {
                confirmation = "Bearish";
            } else {
                continue;
            }

            String levelKey = context.instrument() + ":" + context.timeframe()
                    + ":cmf_confirm:" + candidate.label() + ":" + normalize(candidate.price());

            if (candle.equals(lastFiredCandle.get(levelKey))) continue;
            lastFiredCandle.put(levelKey, candle);

            signals.add(new BehaviourAlertSignal(
                    "cmf:confirm:" + candidate.label() + ":" + context.instrument() + ":" + context.timeframe(),
                    BehaviourAlertCategory.CHAIKIN_BEHAVIOUR,
                    String.format("%s [%s] — %s CMF confirmation at %s (%s), CMF %s",
                            context.instrument(), context.timeframe(),
                            confirmation, candidate.label(), candidate.price().toPlainString(),
                            context.cmf().toPlainString()),
                    context.instrument(),
                    Instant.now()
            ));
        }

        return signals;
    }

    private List<CandidateLevel> buildCandidates(BehaviourAlertContext context) {
        List<CandidateLevel> candidates = new ArrayList<>();
        if (context.ema50() != null) {
            candidates.add(new CandidateLevel("EMA50", context.ema50()));
        }
        if (context.ema200() != null) {
            candidates.add(new CandidateLevel("EMA200", context.ema200()));
        }
        if (context.srLevels() != null) {
            for (SrLevel sr : context.srLevels()) {
                candidates.add(new CandidateLevel(sr.levelType(), sr.price()));
            }
        }
        return candidates;
    }

    private boolean isSupport(CandidateLevel candidate, BigDecimal price) {
        if (SUPPORT_TYPES.contains(candidate.label())) return true;
        if (RESISTANCE_TYPES.contains(candidate.label())) return false;
        // EMA50 / EMA200: support if price is above the level, resistance if below
        return price.compareTo(candidate.price()) > 0;
    }

    private static String normalize(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }

    private record CandidateLevel(String label, BigDecimal price) {}
}
