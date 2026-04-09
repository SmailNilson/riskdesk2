package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.SignalWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Pre-filters indicator alerts before they reach the mentor review pipeline.
 *
 * Rule 1 — HTF Trend Filter:
 *   If H1 trend is BULLISH, block all SHORT signals on lower timeframes (10m).
 *   If H1 trend is BEARISH, block all LONG signals on lower timeframes (10m).
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

    /** Tracks recent signal directions per instrument+timeframe key for anti-chop detection. */
    private final Map<DirectionKey, Deque<DirectionEntry>> recentDirections = new ConcurrentHashMap<>();

    public record FilterResult(boolean allowed, String reason) {}

    private record DirectionKey(String instrument, String timeframe) {}

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
            DirectionKey key = new DirectionKey(alert.instrument(), timeframe);
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
     * @param h1Trend   H1 marketStructureTrend ("BULLISH" / "BEARISH" / "UNDEFINED")
     */
    public List<Alert> filter(List<Alert> alerts, String timeframe, String h1Trend) {
        if (alerts.isEmpty()) return alerts;
        boolean isLtf = !"4h".equals(timeframe);

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
            if ("BULLISH".equals(h1Trend) && "SHORT".equals(direction)) {
                return new FilterResult(false,
                        "HTF BULLISH blocks SHORT signal on " + timeframe);
            }
            if ("BEARISH".equals(h1Trend) && "LONG".equals(direction)) {
                return new FilterResult(false,
                        "HTF BEARISH blocks LONG signal on " + timeframe);
            }
        }

        // Rule 3: Anti-Chop — cancel signal if the opposite direction fired within the chop window
        // Exception: standalone signals (weight >= 3.0) bypass anti-chop — they represent
        // genuine structural breaks (CHoCH, BOS, WT cross) that should not be suppressed
        if (direction != null) {
            SignalWeight sw = SignalWeight.fromAlert(alert);
            boolean isStandaloneSignal = sw != null && sw.weight() >= 3.0f;
            if (!isStandaloneSignal) {
                String opposite = "LONG".equals(direction) ? "SHORT" : "LONG";
                DirectionKey key = new DirectionKey(alert.instrument(), timeframe);
                if (hasRecentSignal(key, opposite)) {
                    log.warn("Market Chop detected - Signals cancelled [{} {} {}]",
                            alert.instrument(), timeframe, direction);
                    return new FilterResult(false,
                            "Anti-chop: conflicting " + opposite + " signal within " + CHOP_WINDOW_SECONDS + "s");
                }
            }
        }

        return new FilterResult(true, null);
    }

    private boolean hasRecentSignal(DirectionKey key, String direction) {
        Deque<DirectionEntry> deque = recentDirections.get(key);
        if (deque == null || deque.isEmpty()) return false;
        Instant cutoff = Instant.now().minusSeconds(CHOP_WINDOW_SECONDS);
        // Prune stale entries while checking
        deque.removeIf(e -> e.timestamp().isBefore(cutoff));
        return deque.stream().anyMatch(e -> direction.equals(e.direction()));
    }

    // ── Direction inference (data-driven) ───────────────────────────────────

    /**
     * A direction rule: if the alert message matches longPattern → LONG,
     * if it matches shortPattern → SHORT, otherwise null.
     */
    private record DirectionRule(AlertCategory category, Pattern longPattern, Pattern shortPattern) {
        String match(String message) {
            if (longPattern.matcher(message).find())  return "LONG";
            if (shortPattern.matcher(message).find()) return "SHORT";
            return null;
        }
    }

    private static Pattern p(String regex) { return Pattern.compile(regex); }

    /** Ordered list of direction extraction rules. First match wins. */
    private static final Map<AlertCategory, DirectionRule> DIRECTION_RULES = Map.ofEntries(
        Map.entry(AlertCategory.ORDER_BLOCK,  new DirectionRule(AlertCategory.ORDER_BLOCK,  p("BULLISH"),                      p("BEARISH"))),
        Map.entry(AlertCategory.ORDER_BLOCK_VWAP, new DirectionRule(AlertCategory.ORDER_BLOCK_VWAP, p("BULLISH"),              p("BEARISH"))),
        Map.entry(AlertCategory.SMC,          new DirectionRule(AlertCategory.SMC,          p("BULLISH"),                      p("BEARISH"))),
        Map.entry(AlertCategory.EMA,          new DirectionRule(AlertCategory.EMA,          p("Golden Cross"),                 p("Death Cross"))),
        Map.entry(AlertCategory.MACD,         new DirectionRule(AlertCategory.MACD,         p("Bullish"),                      p("Bearish"))),
        Map.entry(AlertCategory.WAVETREND,    new DirectionRule(AlertCategory.WAVETREND,    p("Bullish Cross|oversold"),       p("Bearish Cross|overbought"))),
        Map.entry(AlertCategory.RSI,          new DirectionRule(AlertCategory.RSI,          p("oversold"),                     p("overbought"))),
        Map.entry(AlertCategory.SUPERTREND,   new DirectionRule(AlertCategory.SUPERTREND,   p("UPTREND"),                      p("DOWNTREND"))),
        Map.entry(AlertCategory.VWAP_CROSS,   new DirectionRule(AlertCategory.VWAP_CROSS,   p("above"),                        p("below"))),
        Map.entry(AlertCategory.FVG,          new DirectionRule(AlertCategory.FVG,          p("Bullish"),                      p("Bearish"))),
        Map.entry(AlertCategory.EQUAL_LEVEL,  new DirectionRule(AlertCategory.EQUAL_LEVEL,  p("EQL"),                          p("EQH"))),
        Map.entry(AlertCategory.DELTA_FLOW,   new DirectionRule(AlertCategory.DELTA_FLOW,   p("BUYING"),                       p("SELLING"))),
        Map.entry(AlertCategory.CHAIKIN,      new DirectionRule(AlertCategory.CHAIKIN,      p("Bullish"),                      p("Bearish"))),
        Map.entry(AlertCategory.MTF_LEVEL,    new DirectionRule(AlertCategory.MTF_LEVEL,    p("above"),                        p("below")))
    );

    /**
     * Infers LONG / SHORT bias from an alert's category and message.
     * Returns null for signals with no clear directional bias (e.g. BOLLINGER).
     */
    public static String extractDirection(Alert alert) {
        DirectionRule rule = DIRECTION_RULES.get(alert.category());
        if (rule == null) return null;
        return rule.match(alert.message());
    }

    private record DirectionEntry(String direction, Instant timestamp) {}
}
