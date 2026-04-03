package com.riskdesk.domain.behaviouralert.rule;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertCategory;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires a WARNING signal when CMF enters an extreme zone:
 * - Accumulation: CMF > +0.25
 * - Distribution: CMF < -0.25
 *
 * Transition-based: fires only on NEUTRAL→ACCUMULATION or NEUTRAL→DISTRIBUTION.
 * Candle-close guarded.
 */
public class ExtremeCmfZoneRule implements BehaviourAlertRule {

    static final BigDecimal ACCUMULATION_THRESHOLD = new BigDecimal("0.25");
    static final BigDecimal DISTRIBUTION_THRESHOLD = new BigDecimal("-0.25");

    private static final String ACCUMULATION = "ACCUMULATION";
    private static final String DISTRIBUTION = "DISTRIBUTION";
    private static final String NEUTRAL = "NEUTRAL";

    private final ConcurrentHashMap<String, String> lastState = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastFiredCandle = new ConcurrentHashMap<>();

    @Override
    public List<BehaviourAlertSignal> evaluate(BehaviourAlertContext context) {
        if (context.cmf() == null) {
            return Collections.emptyList();
        }

        String state;
        if (context.cmf().compareTo(ACCUMULATION_THRESHOLD) > 0) {
            state = ACCUMULATION;
        } else if (context.cmf().compareTo(DISTRIBUTION_THRESHOLD) < 0) {
            state = DISTRIBUTION;
        } else {
            state = NEUTRAL;
        }

        String key = context.instrument() + ":" + context.timeframe() + ":cmf_extreme";
        String previous = lastState.get(key);

        if (NEUTRAL.equals(state)) {
            lastState.put(key, NEUTRAL);
            return Collections.emptyList();
        }
        if (state.equals(previous)) {
            return Collections.emptyList();
        }

        Instant candle = context.lastCandleTimestamp();
        if (candle == null || candle.equals(lastFiredCandle.get(key))) {
            return Collections.emptyList();
        }
        lastState.put(key, state);
        lastFiredCandle.put(key, candle);

        String direction = ACCUMULATION.equals(state) ? "accumulation" : "distribution";
        return List.of(new BehaviourAlertSignal(
                "cmf:extreme:" + context.instrument() + ":" + context.timeframe(),
                BehaviourAlertCategory.CHAIKIN_BEHAVIOUR,
                String.format("%s [%s] — Strong %s: CMF at %s",
                        context.instrument(), context.timeframe(),
                        direction, context.cmf().toPlainString()),
                context.instrument(),
                Instant.now()
        ));
    }
}
