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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires a WARNING signal when price is within 0.15% of a known S/R level.
 * Covers: EQH, EQL, STRONG_HIGH, STRONG_LOW, WEAK_HIGH, WEAK_LOW.
 * Each level is tracked independently. Transition-based and candle-close guarded.
 */
public class SupportResistanceTouchRule implements BehaviourAlertRule {

    static final BigDecimal PROXIMITY_THRESHOLD = new BigDecimal("0.003");

    private final ConcurrentHashMap<String, String> lastState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastFiredCandle = new ConcurrentHashMap<>();

    @Override
    public List<BehaviourAlertSignal> evaluate(BehaviourAlertContext context) {
        if (context.lastPrice() == null || context.srLevels() == null || context.srLevels().isEmpty()) {
            return Collections.emptyList();
        }

        List<BehaviourAlertSignal> signals = new ArrayList<>();

        for (SrLevel level : context.srLevels()) {
            if (level.price() == null || level.price().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal pct = context.lastPrice().subtract(level.price()).abs()
                    .divide(level.price(), 6, RoundingMode.HALF_UP);
            String state = pct.compareTo(PROXIMITY_THRESHOLD) < 0 ? "NEAR" : "FAR";
            String key = context.instrument() + ":" + context.timeframe()
                    + ":sr:" + level.levelType() + ":" + normalize(level.price());

            String previous = lastState.get(key);  // peek — do not update yet

            if (!"NEAR".equals(state)) {
                lastState.put(key, "FAR");  // reset so future NEAR can transition
                continue;
            }
            if ("NEAR".equals(previous)) {
                continue;  // already near, no new transition
            }

            // FAR → NEAR: candle-close guard before committing state
            Instant candle = context.lastCandleTimestamp();
            if (candle == null || candle.equals(lastFiredCandle.get(key))) {
                continue;  // guard blocks — retry on next closed candle
            }
            lastState.put(key, "NEAR");
            lastFiredCandle.put(key, candle);

            signals.add(new BehaviourAlertSignal(
                    "sr:" + level.levelType().toLowerCase() + ":" + normalize(level.price())
                            + ":" + context.instrument() + ":" + context.timeframe(),
                    BehaviourAlertCategory.SUPPORT_RESISTANCE,
                    String.format("%s [%s] — Price touching %s level at %s (price %s)",
                            context.instrument(), context.timeframe(),
                            level.levelType(), level.price().toPlainString(),
                            context.lastPrice().toPlainString()),
                    context.instrument(),
                    Instant.now()
            ));
        }

        return signals;
    }

    private static String normalize(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }
}
