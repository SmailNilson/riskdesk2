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
 * Fires a WARNING signal when price is within 0.15% of EMA200.
 * EMA200 is a major macro level — tracked independently from EMA50.
 * Transition-based and candle-close guarded.
 */
public class Ema200ProximityRule implements BehaviourAlertRule {

    static final BigDecimal PROXIMITY_THRESHOLD = new BigDecimal("0.0015");

    private final ConcurrentHashMap<String, String> lastState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastFiredCandle = new ConcurrentHashMap<>();

    @Override
    public List<BehaviourAlertSignal> evaluate(BehaviourAlertContext context) {
        if (context.lastPrice() == null || context.ema200() == null
                || context.ema200().compareTo(BigDecimal.ZERO) == 0) {
            return Collections.emptyList();
        }

        BigDecimal pct = context.lastPrice().subtract(context.ema200()).abs()
                .divide(context.ema200(), 6, RoundingMode.HALF_UP);
        String state = pct.compareTo(PROXIMITY_THRESHOLD) < 0 ? "NEAR" : "FAR";
        String key = context.instrument() + ":" + context.timeframe() + ":ema200";

        String previous = lastState.get(key);  // peek — do not update yet

        if (!"NEAR".equals(state)) {
            lastState.put(key, "FAR");
            return Collections.emptyList();
        }
        if ("NEAR".equals(previous)) {
            return Collections.emptyList();
        }

        // FAR → NEAR: candle-close guard before committing state
        Instant candle = context.lastCandleTimestamp();
        if (candle == null || candle.equals(lastFiredCandle.get(key))) {
            return Collections.emptyList();
        }
        lastState.put(key, "NEAR");
        lastFiredCandle.put(key, candle);

        return List.of(new BehaviourAlertSignal(
                "ema200:proximity:" + context.instrument() + ":" + context.timeframe(),
                BehaviourAlertCategory.EMA_PROXIMITY,
                String.format("%s [%s] — Price approaching EMA200 (%.5f vs EMA200 %.5f)",
                        context.instrument(), context.timeframe(),
                        context.lastPrice(), context.ema200()),
                context.instrument(),
                Instant.now()
        ));
    }
}
