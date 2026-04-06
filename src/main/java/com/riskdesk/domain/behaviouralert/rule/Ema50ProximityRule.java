package com.riskdesk.domain.behaviouralert.rule;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertCategory;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires a WARNING signal when price is within 0.15% of EMA50.
 * Transition-based: fires only when state changes FAR → NEAR, not on every tick.
 * Candle-close guarded: will not fire twice on the same candle.
 */
public class Ema50ProximityRule implements BehaviourAlertRule {

    static final BigDecimal PROXIMITY_THRESHOLD = new BigDecimal("0.003");

    private final ConcurrentHashMap<String, String> lastState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastFiredCandle = new ConcurrentHashMap<>();

    @Override
    public List<BehaviourAlertSignal> evaluate(BehaviourAlertContext context) {
        if (context.lastPrice() == null || context.ema50() == null
                || context.ema50().compareTo(BigDecimal.ZERO) == 0) {
            return Collections.emptyList();
        }

        BigDecimal pct = context.lastPrice().subtract(context.ema50()).abs()
                .divide(context.ema50(), 6, RoundingMode.HALF_UP);
        String state = pct.compareTo(PROXIMITY_THRESHOLD) < 0 ? "NEAR" : "FAR";
        String key = context.instrument() + ":" + context.timeframe() + ":ema50";

        String previous = lastState.get(key);  // peek — do not update yet

        if (!"NEAR".equals(state)) {
            lastState.put(key, "FAR");  // reset so future NEAR can transition
            return Collections.emptyList();
        }
        if ("NEAR".equals(previous)) {
            return Collections.emptyList();  // already near, no new transition
        }

        // FAR → NEAR transition: apply candle-close guard before committing state
        Instant candle = context.lastCandleTimestamp();
        if (candle == null || candle.equals(lastFiredCandle.get(key))) {
            return Collections.emptyList();  // guard blocks — do NOT update state; retry on next candle
        }
        lastState.put(key, "NEAR");
        lastFiredCandle.put(key, candle);

        return List.of(new BehaviourAlertSignal(
                "ema50:proximity:" + context.instrument() + ":" + context.timeframe(),
                BehaviourAlertCategory.EMA_PROXIMITY,
                String.format("%s [%s] — Price approaching EMA50 (%.5f vs EMA50 %.5f)",
                        context.instrument(), context.timeframe(),
                        context.lastPrice(), context.ema50()),
                context.instrument(),
                Instant.now()
        ));
    }
}
