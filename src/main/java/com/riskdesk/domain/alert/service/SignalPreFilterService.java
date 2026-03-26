package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-filters indicator alerts before they reach the mentor review pipeline.
 *
 * Rule 1 — HTF Trend Filter:
 *   If H1 trend is UPTREND, block all SHORT signals on lower timeframes (10m).
 *   If H1 trend is DOWNTREND, block all LONG signals on lower timeframes (10m).
 *
 * Rule 3 — Anti-Chop (Conflicting Signal Suppression):
 *   If both LONG and SHORT signals appear for the same instrument within 60 seconds,
 *   the market is in range. Both signals are cancelled with a "Market Chop detected" log.
 *
 * Rule 4 (Candle Close) is enforced inside IndicatorAlertEvaluator via candle timestamp tracking.
 * Rule 2 (Dynamic Cooldown) is enforced inside AlertDeduplicator via timeframe-aware shouldFire().
 */
public class SignalPreFilterService {

    private static final Logger log = LoggerFactory.getLogger(SignalPreFilterService.class);
    private static final long CHOP_WINDOW_SECONDS = 60;

    /** Tracks recent signal directions per "instrument:timeframe" key for anti-chop detection. */
    private final Map<String, Deque<DirectionEntry>> recentDirections = new ConcurrentHashMap<>();

    public record FilterResult(boolean allowed, String reason) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Must be called BEFORE filter() to register all candidate signals (including those
     * that may later be blocked) so that anti-chop detection has full visibility.
     */
    public void recordSignals(List<Alert> alerts, String timeframe) {
        Instant now = Instant.now();
        for (Alert alert : alerts) {
            String direction = extractDirection(alert);
            if (direction == null) continue;
            String key = alert.instrument() + ":" + timeframe;
            recentDirections.computeIfAbsent(key, k -> new ArrayDeque<>())
                    .addLast(new DirectionEntry(direction, now));
        }
    }

    /**
     * Applies HTF trend alignment (Rule 1) and anti-chop suppression (Rule 3).
     * Returns only the alerts that pass all filters.
     *
     * @param alerts    Candidate alerts for a given instrument+timeframe
     * @param timeframe The LTF timeframe label (e.g. "10m")
     * @param h1Trend   H1 marketStructureTrend ("UPTREND" / "DOWNTREND" / "UNDEFINED")
     */
    public List<Alert> filter(List<Alert> alerts, String timeframe, String h1Trend) {
        if (alerts.isEmpty()) return alerts;
        boolean isLtf = !"1h".equals(timeframe);

        return alerts.stream()
                .filter(alert -> {
                    FilterResult r = applyFilters(alert, timeframe, h1Trend, isLtf);
                    if (!r.allowed()) {
                        log.debug("PRE-FILTER [{}] blocked '{}' — {}", timeframe, alert.message(), r.reason());
                    }
                    return r.allowed();
                })
                .toList();
    }

    // ── Filter logic ──────────────────────────────────────────────────────────

    private FilterResult applyFilters(Alert alert, String timeframe, String h1Trend, boolean isLtf) {
        String direction = extractDirection(alert);

        // Rule 1: HTF Trend Filter — only applied on lower timeframes with directional signals
        if (isLtf && direction != null) {
            if ("UPTREND".equals(h1Trend) && "SHORT".equals(direction)) {
                return new FilterResult(false,
                        "HTF UPTREND blocks SHORT signal on " + timeframe);
            }
            if ("DOWNTREND".equals(h1Trend) && "LONG".equals(direction)) {
                return new FilterResult(false,
                        "HTF DOWNTREND blocks LONG signal on " + timeframe);
            }
        }

        // Rule 3: Anti-Chop — cancel signal if the opposite direction fired within the chop window
        if (direction != null) {
            String opposite = "LONG".equals(direction) ? "SHORT" : "LONG";
            String key = alert.instrument() + ":" + timeframe;
            if (hasRecentSignal(key, opposite)) {
                log.warn("Market Chop detected - Signals cancelled [{} {} {}]",
                        alert.instrument(), timeframe, direction);
                return new FilterResult(false,
                        "Anti-chop: conflicting " + opposite + " signal within " + CHOP_WINDOW_SECONDS + "s");
            }
        }

        return new FilterResult(true, null);
    }

    private boolean hasRecentSignal(String key, String direction) {
        Deque<DirectionEntry> deque = recentDirections.get(key);
        if (deque == null || deque.isEmpty()) return false;
        Instant cutoff = Instant.now().minusSeconds(CHOP_WINDOW_SECONDS);
        // Prune stale entries while checking
        deque.removeIf(e -> e.timestamp().isBefore(cutoff));
        return deque.stream().anyMatch(e -> direction.equals(e.direction()));
    }

    // ── Direction inference ───────────────────────────────────────────────────

    /**
     * Infers LONG / SHORT bias from an alert's category and message.
     * Returns null for signals with no clear directional bias (e.g. pure volatility alerts).
     */
    static String extractDirection(Alert alert) {
        if (alert.category() == AlertCategory.SMC) {
            if (alert.message().contains("BULLISH")) return "LONG";
            if (alert.message().contains("BEARISH")) return "SHORT";
        }
        if (alert.category() == AlertCategory.EMA) {
            if (alert.message().contains("Golden Cross")) return "LONG";
            if (alert.message().contains("Death Cross"))  return "SHORT";
        }
        if (alert.category() == AlertCategory.MACD) {
            if (alert.message().contains("Bullish")) return "LONG";
            if (alert.message().contains("Bearish")) return "SHORT";
        }
        if (alert.category() == AlertCategory.WAVETREND) {
            if (alert.message().contains("Bullish Cross") || alert.message().contains("oversold"))  return "LONG";
            if (alert.message().contains("Bearish Cross") || alert.message().contains("overbought")) return "SHORT";
        }
        if (alert.category() == AlertCategory.RSI) {
            if (alert.message().contains("oversold"))   return "LONG";
            if (alert.message().contains("overbought")) return "SHORT";
        }
        return null;
    }

    private record DirectionEntry(String direction, Instant timestamp) {}
}
