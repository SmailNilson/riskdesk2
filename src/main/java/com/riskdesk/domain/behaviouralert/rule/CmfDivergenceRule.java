package com.riskdesk.domain.behaviouralert.rule;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertCategory;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects price/CMF divergence using an internal 5-bar ring buffer.
 * <ul>
 *   <li>Bearish divergence: price makes higher swing high but CMF is lower.</li>
 *   <li>Bullish divergence: price makes lower swing low but CMF is higher.</li>
 * </ul>
 * Swing detection uses a simple 3-bar lookback: a point at index {@code i} is a swing high
 * if {@code price[i] > price[i-1] AND price[i] > price[i+1]}.
 * <p>Candle-close guarded and transition-based (does not re-fire same divergence type).
 */
public class CmfDivergenceRule implements BehaviourAlertRule {

    private static final int BUFFER_SIZE = 5;

    private record PricePoint(BigDecimal price, BigDecimal cmf, Instant candle) {}

    private final ConcurrentHashMap<String, ArrayDeque<PricePoint>> history = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastDivergence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastFiredCandle = new ConcurrentHashMap<>();

    @Override
    public List<BehaviourAlertSignal> evaluate(BehaviourAlertContext context) {
        if (context.lastPrice() == null || context.cmf() == null) {
            return Collections.emptyList();
        }

        Instant candle = context.lastCandleTimestamp();
        if (candle == null) {
            return Collections.emptyList();
        }

        String key = context.instrument() + ":" + context.timeframe() + ":cmf_divergence";
        ArrayDeque<PricePoint> buffer = history.computeIfAbsent(key, k -> new ArrayDeque<>());

        // Append new data point only if it's a new candle
        if (buffer.isEmpty() || !candle.equals(buffer.peekLast().candle())) {
            buffer.addLast(new PricePoint(context.lastPrice(), context.cmf(), candle));
            if (buffer.size() > BUFFER_SIZE) {
                buffer.removeFirst();
            }
        }

        if (buffer.size() < 3) {
            return Collections.emptyList();
        }

        PricePoint[] points = buffer.toArray(PricePoint[]::new);
        String divergence = detectDivergence(points);

        if (divergence == null) {
            return Collections.emptyList();
        }

        String previous = lastDivergence.get(key);
        if (divergence.equals(previous)) {
            return Collections.emptyList();
        }

        if (candle.equals(lastFiredCandle.get(key))) {
            return Collections.emptyList();
        }
        lastDivergence.put(key, divergence);
        lastFiredCandle.put(key, candle);

        String direction = "BULLISH".equals(divergence) ? "Bullish" : "Bearish";
        return List.of(new BehaviourAlertSignal(
                "cmf:divergence:" + divergence.toLowerCase() + ":" + context.instrument() + ":" + context.timeframe(),
                BehaviourAlertCategory.CHAIKIN_BEHAVIOUR,
                String.format("%s [%s] — %s CMF divergence detected (CMF %s)",
                        context.instrument(), context.timeframe(),
                        direction, context.cmf().toPlainString()),
                context.instrument(),
                Instant.now()
        ));
    }

    private String detectDivergence(PricePoint[] points) {
        // Find two most recent swing highs for bearish divergence
        PricePoint swingHigh1 = null;
        PricePoint swingHigh2 = null;
        for (int i = points.length - 2; i >= 1; i--) {
            if (points[i].price().compareTo(points[i - 1].price()) > 0
                    && points[i].price().compareTo(points[i + 1].price()) > 0) {
                if (swingHigh2 == null) {
                    swingHigh2 = points[i]; // most recent
                } else {
                    swingHigh1 = points[i]; // older
                    break;
                }
            }
        }

        if (swingHigh1 != null
                && swingHigh2.price().compareTo(swingHigh1.price()) > 0
                && swingHigh2.cmf().compareTo(swingHigh1.cmf()) < 0) {
            return "BEARISH";
        }

        // Find two most recent swing lows for bullish divergence
        PricePoint swingLow1 = null;
        PricePoint swingLow2 = null;
        for (int i = points.length - 2; i >= 1; i--) {
            if (points[i].price().compareTo(points[i - 1].price()) < 0
                    && points[i].price().compareTo(points[i + 1].price()) < 0) {
                if (swingLow2 == null) {
                    swingLow2 = points[i];
                } else {
                    swingLow1 = points[i];
                    break;
                }
            }
        }

        if (swingLow1 != null
                && swingLow2.price().compareTo(swingLow1.price()) < 0
                && swingLow2.cmf().compareTo(swingLow1.cmf()) > 0) {
            return "BULLISH";
        }

        return null;
    }
}
