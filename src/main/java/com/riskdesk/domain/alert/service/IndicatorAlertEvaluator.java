package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.alert.model.IndicatorAlertSnapshot;
import com.riskdesk.domain.alert.port.AlertStateStore;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Evaluates indicator snapshots and produces domain Alert objects.
 * Uses transition detection: alerts fire only when a condition CHANGES,
 * not when it persists across polling cycles.
 */
public class IndicatorAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(IndicatorAlertEvaluator.class);
    private static final int MAX_ORDER_BLOCK_EVENT_KEYS = 5_000;

    private final AlertStateStore stateStore;

    /**
     * Tracks the last known state for each indicator per instrument+timeframe.
     */
    private final Map<EvalKey, String> lastState = new ConcurrentHashMap<>();

    /**
     * Rule 4 — Candle Close Guard.
     * Tracks the last candle timestamp on which each signal fired.
     * Prevents re-firing on the same open candle due to intra-bar repainting.
     */
    private final Map<EvalKey, Instant> lastFiredCandle = new ConcurrentHashMap<>();

    /**
     * OB lifecycle events are one-shot events, not persistent transitions.
     * Keep a bounded set of recently-seen event keys so long-lived runtimes
     * cannot retain every historical OB price level forever.
     */
    private final Map<EvalKey, Instant> seenOrderBlockEvents = new ConcurrentHashMap<>();
    private final Deque<EvalKey> orderBlockEventOrder = new ArrayDeque<>();
    private final Object orderBlockEventLock = new Object();

    /** No-op store for tests — no persistence, no recovery. */
    private static final AlertStateStore NOOP_STORE = new AlertStateStore() {
        @Override public Map<String, String> loadRecent() { return Map.of(); }
        @Override public void save(String evalKey, String signal) { }
        @Override public void remove(String evalKey) { }
    };

    /** Creates an evaluator without persistent state (for tests). */
    public IndicatorAlertEvaluator() {
        this(NOOP_STORE);
    }

    /**
     * Creates an evaluator with persistent state recovery.
     * Loads recent states from the store (only states < 12h old to avoid stale weekend data).
     */
    public IndicatorAlertEvaluator(AlertStateStore stateStore) {
        this.stateStore = stateStore;
        Map<String, String> recovered = stateStore.loadRecent();
        for (Map.Entry<String, String> entry : recovered.entrySet()) {
            EvalKey key = parseEvalKey(entry.getKey());
            if (key != null) {
                lastState.put(key, entry.getValue());
            }
        }
        if (!recovered.isEmpty()) {
            log.info("IndicatorAlertEvaluator: recovered {} signal states from DB.", recovered.size());
        }
    }

    static String serializeEvalKey(EvalKey k) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(k.family()).append(':').append(k.instrument()).append(':').append(k.timeframe());
        sb.append(':').append(k.qualifier() == null ? "" : k.qualifier());
        if (k.high() != null) {
            sb.append(':').append(k.high().toPlainString()).append(':').append(k.low() == null ? "" : k.low().toPlainString());
        }
        String result = sb.toString();
        if (result.length() > 255) {
            result = result.substring(0, 255);
        }
        return result;
    }

    static EvalKey parseEvalKey(String serialized) {
        if (serialized == null || serialized.isBlank()) return null;
        String[] parts = serialized.split(":", -1);
        if (parts.length < 4) return null;
        String family = parts[0];
        String instrument = parts[1];
        String timeframe = parts[2];
        String qualifier = parts[3].isEmpty() ? null : parts[3];
        BigDecimal high = null;
        BigDecimal low = null;
        if (parts.length >= 6) {
            try {
                high = parts[4].isEmpty() ? null : new BigDecimal(parts[4]);
                low = parts[5].isEmpty() ? null : new BigDecimal(parts[5]);
            } catch (NumberFormatException e) {
                // Ignore malformed price data — key still usable without price
            }
        }
        return new EvalKey(family, instrument, timeframe, qualifier, high, low);
    }

    private record EvalKey(
            String family,
            String instrument,
            String timeframe,
            String qualifier,
            BigDecimal high,
            BigDecimal low
    ) {}

    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static EvalKey key(String family, Instrument instrument, String timeframe, String qualifier) {
        return new EvalKey(family, instrument.name(), timeframe, qualifier, null, null);
    }

    private static EvalKey key(String family, Instrument instrument, String timeframe, String qualifier,
                               BigDecimal high, BigDecimal low) {
        return new EvalKey(family, instrument.name(), timeframe, qualifier, normalize(high), normalize(low));
    }

    private static EvalKey candleKey(EvalKey baseKey) {
        String qualifier = baseKey.qualifier() == null ? "candle" : baseKey.qualifier() + ":candle";
        return new EvalKey(baseKey.family(), baseKey.instrument(), baseKey.timeframe(), qualifier,
                baseKey.high(), baseKey.low());
    }

    /**
     * Returns true if this is a new candle (different from the last candle on which this
     * signal fired), and records the current candle timestamp. Returns false if no timestamp is
     * available (fail-closed: no timestamp means candle close is not confirmed).
     */
    private boolean canFireOnCandle(EvalKey candleKey, Instant lastCandleTimestamp) {
        if (lastCandleTimestamp == null) return false;
        Instant prev = lastFiredCandle.get(candleKey);
        if (lastCandleTimestamp.equals(prev)) return false;
        lastFiredCandle.put(candleKey, lastCandleTimestamp);
        return true;
    }

    /**
     * Returns true if the signal is new (different from last known state)
     * and updates the tracking map. Returns false if signal is unchanged or null.
     * Persists state changes to the store for restart recovery.
     */
    private boolean isTransition(EvalKey stateKey, String currentSignal) {
        if (currentSignal == null) {
            lastState.remove(stateKey);
            persistRemove(stateKey);
            return false;
        }
        String previous = lastState.put(stateKey, currentSignal);
        if (!currentSignal.equals(previous)) {
            persistSave(stateKey, currentSignal);
            return true;
        }
        return false;
    }

    /**
     * For candle-guarded signals, only commit the new state after the candle check passes.
     * This avoids consuming an intra-bar transition that is later rejected as unconfirmed.
     */
    private boolean isConfirmedTransition(EvalKey stateKey, String currentSignal,
                                          EvalKey candleKey, Instant lastCandleTimestamp) {
        if (currentSignal == null) {
            lastState.remove(stateKey);
            persistRemove(stateKey);
            return false;
        }
        String previous = lastState.get(stateKey);
        if (currentSignal.equals(previous)) {
            return false;
        }
        if (!canFireOnCandle(candleKey, lastCandleTimestamp)) {
            return false;
        }
        lastState.put(stateKey, currentSignal);
        persistSave(stateKey, currentSignal);
        return true;
    }

    private boolean shouldFireOrderBlockEvent(EvalKey eventKey, Instant lastCandleTimestamp) {
        EvalKey eventCandleKey = candleKey(eventKey);
        if (!canFireOnCandle(eventCandleKey, lastCandleTimestamp)) {
            return false;
        }
        Instant previous = seenOrderBlockEvents.putIfAbsent(eventKey, lastCandleTimestamp);
        if (previous != null) {
            return false;
        }

        synchronized (orderBlockEventLock) {
            orderBlockEventOrder.addLast(eventKey);
            while (orderBlockEventOrder.size() > MAX_ORDER_BLOCK_EVENT_KEYS) {
                EvalKey evicted = orderBlockEventOrder.removeFirst();
                seenOrderBlockEvents.remove(evicted);
                lastFiredCandle.remove(candleKey(evicted));
            }
        }
        return true;
    }

    public List<Alert> evaluate(Instrument instrument, String timeframe, IndicatorAlertSnapshot snap) {
        List<Alert> alerts = new ArrayList<>();
        if (snap == null) return alerts;
        String tf = instrument.name() + ":" + timeframe;

        // EMA crossover — only on confirmed transition (Rule 4: candle close guard)
        EvalKey emaKey = key("ema", instrument, timeframe, null);
        if (isConfirmedTransition(emaKey, snap.emaCrossover(), candleKey(emaKey), snap.lastCandleTimestamp())) {
            if ("GOLDEN_CROSS".equals(snap.emaCrossover())) {
                alerts.add(new Alert("ema:golden:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — EMA Golden Cross (9 x 50)",
                    AlertCategory.EMA, instrument.name()));
            } else if ("DEATH_CROSS".equals(snap.emaCrossover())) {
                alerts.add(new Alert("ema:death:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — EMA Death Cross (9 x 50)",
                    AlertCategory.EMA, instrument.name()));
            }
        }

        // RSI extremes — only on confirmed transition (Rule 4: candle close guard)
        EvalKey rsiKey = key("rsi", instrument, timeframe, null);
        if (isConfirmedTransition(rsiKey, snap.rsiSignal(), candleKey(rsiKey), snap.lastCandleTimestamp())) {
            if ("OVERSOLD".equals(snap.rsiSignal())) {
                alerts.add(new Alert("rsi:oversold:" + tf, AlertSeverity.INFO,
                    String.format("%s [%s] — RSI oversold at %.1f", instrument.getDisplayName(), timeframe,
                        snap.rsi() != null ? snap.rsi().doubleValue() : 0),
                    AlertCategory.RSI, instrument.name()));
            } else if ("OVERBOUGHT".equals(snap.rsiSignal())) {
                alerts.add(new Alert("rsi:overbought:" + tf, AlertSeverity.INFO,
                    String.format("%s [%s] — RSI overbought at %.1f", instrument.getDisplayName(), timeframe,
                        snap.rsi() != null ? snap.rsi().doubleValue() : 0),
                    AlertCategory.RSI, instrument.name()));
            }
        }

        // MACD crossover — only on confirmed transition (Rule 4: candle close guard)
        EvalKey macdKey = key("macd", instrument, timeframe, null);
        if (isConfirmedTransition(macdKey, snap.macdCrossover(), candleKey(macdKey), snap.lastCandleTimestamp())) {
            if ("BULLISH_CROSS".equals(snap.macdCrossover())) {
                alerts.add(new Alert("macd:bullish:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — MACD Bullish Cross",
                    AlertCategory.MACD, instrument.name()));
            } else if ("BEARISH_CROSS".equals(snap.macdCrossover())) {
                alerts.add(new Alert("macd:bearish:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — MACD Bearish Cross",
                    AlertCategory.MACD, instrument.name()));
            }
        }

        // SMC Internal — only on transition AND on a new candle (Rule 4: candle close guard)
        EvalKey smcInternalKey = key("smc", instrument, timeframe, "internal");
        if (isConfirmedTransition(smcInternalKey, snap.lastInternalBreakType(),
                candleKey(smcInternalKey), snap.lastCandleTimestamp())) {
            if (snap.lastInternalBreakType().startsWith("CHOCH")) {
                alerts.add(new Alert("smc:internal:choch:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — Internal CHoCH: " + snap.lastInternalBreakType(),
                    AlertCategory.SMC, instrument.name()));
            } else if ("BOS".equals(snap.lastInternalBreakType()) || snap.lastInternalBreakType().startsWith("BOS")) {
                alerts.add(new Alert("smc:internal:bos:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — Internal BOS: " + snap.lastInternalBreakType(),
                    AlertCategory.SMC, instrument.name()));
            }
        }

        // SMC Swing — only on transition AND on a new candle (Rule 4: candle close guard)
        EvalKey smcSwingKey = key("smc", instrument, timeframe, "swing");
        if (isConfirmedTransition(smcSwingKey, snap.lastSwingBreakType(),
                candleKey(smcSwingKey), snap.lastCandleTimestamp())) {
            if (snap.lastSwingBreakType().startsWith("CHOCH")) {
                alerts.add(new Alert("smc:swing:choch:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — Swing CHoCH: " + snap.lastSwingBreakType(),
                    AlertCategory.SMC, instrument.name()));
            } else if ("BOS".equals(snap.lastSwingBreakType()) || snap.lastSwingBreakType().startsWith("BOS")) {
                alerts.add(new Alert("smc:swing:bos:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — Swing BOS: " + snap.lastSwingBreakType(),
                    AlertCategory.SMC, instrument.name()));
            }
        }

        // SMC legacy — kept for backward compat (fires on either level transition)
        EvalKey smcKey = key("smc", instrument, timeframe, "legacy");
        if (isConfirmedTransition(smcKey, snap.lastBreakType(), candleKey(smcKey), snap.lastCandleTimestamp())) {
            if (snap.lastBreakType().startsWith("CHOCH")) {
                alerts.add(new Alert("smc:choch:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — CHoCH detected: " + snap.lastBreakType(),
                    AlertCategory.SMC, instrument.name()));
            } else if ("BOS".equals(snap.lastBreakType()) || snap.lastBreakType().startsWith("BOS")) {
                alerts.add(new Alert("smc:bos:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — BOS: " + snap.lastBreakType(),
                    AlertCategory.SMC, instrument.name()));
            }
        }

        // WaveTrend signal — only on transition AND on a new candle (Rule 4: candle close guard)
        if (snap.wtWt1() != null) {
            EvalKey wtSignalKey = key("wt", instrument, timeframe, "signal");
            if (isConfirmedTransition(wtSignalKey, snap.wtSignal(),
                    candleKey(wtSignalKey), snap.lastCandleTimestamp())) {
                if ("OVERBOUGHT".equals(snap.wtSignal())) {
                    alerts.add(new Alert("wt:overbought:" + tf, AlertSeverity.WARNING,
                        String.format("%s [%s] — WaveTrend overbought (WT1=%.1f)",
                            instrument.getDisplayName(), timeframe, snap.wtWt1().doubleValue()),
                        AlertCategory.WAVETREND, instrument.name()));
                } else if ("OVERSOLD".equals(snap.wtSignal())) {
                    alerts.add(new Alert("wt:oversold:" + tf, AlertSeverity.INFO,
                        String.format("%s [%s] — WaveTrend oversold (WT1=%.1f)",
                            instrument.getDisplayName(), timeframe, snap.wtWt1().doubleValue()),
                        AlertCategory.WAVETREND, instrument.name()));
                }
            }

            // WaveTrend crossover — only on transition AND on a new candle (Rule 4)
            EvalKey wtCrossKey = key("wt", instrument, timeframe, "cross");
            if (isConfirmedTransition(wtCrossKey, snap.wtCrossover(),
                    candleKey(wtCrossKey), snap.lastCandleTimestamp())) {
                if ("BULLISH_CROSS".equals(snap.wtCrossover())) {
                    alerts.add(new Alert("wt:bull:" + tf, AlertSeverity.INFO,
                        instrument.getDisplayName() + " [" + timeframe + "] — WaveTrend Bullish Cross",
                        AlertCategory.WAVETREND, instrument.name()));
                } else if ("BEARISH_CROSS".equals(snap.wtCrossover())) {
                    alerts.add(new Alert("wt:bear:" + tf, AlertSeverity.WARNING,
                        instrument.getDisplayName() + " [" + timeframe + "] — WaveTrend Bearish Cross",
                        AlertCategory.WAVETREND, instrument.name()));
                }
            }
        }

        // Order Block lifecycle events (UC-SMC-009) — fires on real MITIGATION / INVALIDATION
        if (snap.recentObEvents() != null && !snap.recentObEvents().isEmpty()) {
            for (IndicatorAlertSnapshot.OrderBlockEvent evt : snap.recentObEvents()) {
                EvalKey obEventKey = key("ob", instrument, timeframe, evt.eventType() + ":" + evt.obType(),
                        evt.high(), evt.low());
                if (shouldFireOrderBlockEvent(obEventKey, snap.lastCandleTimestamp())) {
                    AlertSeverity sev = "INVALIDATION".equals(evt.eventType())
                            ? AlertSeverity.WARNING : AlertSeverity.INFO;
                    String verb = "MITIGATION".equals(evt.eventType()) ? "mitigated" : "invalidated";
                    alerts.add(new Alert("ob:" + evt.eventType().toLowerCase() + ":" + evt.obType() + ":" + tf, sev,
                        String.format("%s [%s] — %s OB %s [%.5f – %.5f]",
                            instrument.getDisplayName(), timeframe, evt.obType(),
                            verb, evt.low().doubleValue(), evt.high().doubleValue()),
                        AlertCategory.ORDER_BLOCK, instrument.name()));
                }
            }
        }

        // ── V2 transition signals (data-driven) ────────────────────────────
        for (TransitionSignalDef def : V2_TRANSITION_SIGNALS) {
            String signal = def.signalExtractor().apply(snap);
            EvalKey sk = key(def.family(), instrument, timeframe, def.qualifier());
            if (isConfirmedTransition(sk, signal, candleKey(sk), snap.lastCandleTimestamp())) {
                SignalVariant variant = def.variants().get(signal);
                if (variant != null) {
                    alerts.add(new Alert(
                        def.family() + ":" + variant.keySlug() + ":" + tf,
                        variant.severity(),
                        instrument.getDisplayName() + " [" + timeframe + "] — " + variant.label(),
                        def.category(), instrument.name()));
                }
            }
        }

        // ── V2 one-shot events: FVG ──────────────────────────────────────────
        if (snap.recentFvgEvents() != null) {
            for (IndicatorAlertSnapshot.FvgEvent evt : snap.recentFvgEvents()) {
                EvalKey fvgKey = key("fvg", instrument, timeframe, evt.bias(), evt.top(), evt.bottom());
                if (shouldFireOrderBlockEvent(fvgKey, snap.lastCandleTimestamp())) {
                    alerts.add(new Alert("fvg:" + evt.bias().toLowerCase() + ":" + tf, AlertSeverity.INFO,
                        String.format("%s [%s] — FVG %s [%.5f – %.5f]",
                            instrument.getDisplayName(), timeframe, evt.bias(),
                            evt.bottom().doubleValue(), evt.top().doubleValue()),
                        AlertCategory.FVG, instrument.name()));
                }
            }
        }

        // ── V2 one-shot events: EQH/EQL Sweep ───────────────────────────────
        if (snap.recentSweepEvents() != null) {
            for (IndicatorAlertSnapshot.SweepEvent evt : snap.recentSweepEvents()) {
                EvalKey sweepKey = key("eql", instrument, timeframe, evt.type(), evt.price(), evt.price());
                if (shouldFireOrderBlockEvent(sweepKey, snap.lastCandleTimestamp())) {
                    alerts.add(new Alert("eql:" + evt.type().toLowerCase() + ":" + tf, AlertSeverity.WARNING,
                        String.format("%s [%s] — %s sweep at %.5f",
                            instrument.getDisplayName(), timeframe, evt.type(), evt.price().doubleValue()),
                        AlertCategory.EQUAL_LEVEL, instrument.name()));
                }
            }
        }

        // ── V2 MTF Level Cross (data-driven) ────────────────────────────────
        if (snap.close() != null) {
            for (MtfLevelDef mtf : MTF_LEVEL_DEFS) {
                BigDecimal high = mtf.highExtractor().apply(snap);
                BigDecimal low  = mtf.lowExtractor().apply(snap);
                if (high == null || low == null) continue;
                String pos = deriveMtfPosition(snap.close(), high, low);
                EvalKey mk = key("mtf", instrument, timeframe, mtf.qualifier());
                if (isConfirmedTransition(mk, pos, candleKey(mk), snap.lastCandleTimestamp())) {
                    alerts.add(new Alert(
                        "mtf:" + mtf.label() + ":" + pos.toLowerCase() + ":" + tf, AlertSeverity.INFO,
                        String.format("%s [%s] — Price %s %s range [%.5f – %.5f]",
                            instrument.getDisplayName(), timeframe,
                            pos.replace("_", " ").toLowerCase(), mtf.label(),
                            low.doubleValue(), high.doubleValue()),
                        AlertCategory.MTF_LEVEL, instrument.name()));
                }
            }
        }

        return alerts;
    }

    // ── V2 signal definitions (data-driven, no if/else per signal) ────────

    private record SignalVariant(String keySlug, AlertSeverity severity, String label) {}

    private record TransitionSignalDef(
        String family,
        String qualifier,
        AlertCategory category,
        Function<IndicatorAlertSnapshot, String> signalExtractor,
        Map<String, SignalVariant> variants
    ) {}

    private static final List<TransitionSignalDef> V2_TRANSITION_SIGNALS = List.of(
        new TransitionSignalDef("st", null, AlertCategory.SUPERTREND,
            IndicatorAlertSnapshot::supertrendDirection, Map.of(
                "UPTREND",   new SignalVariant("up",   AlertSeverity.INFO,    "Supertrend flipped UPTREND"),
                "DOWNTREND", new SignalVariant("down", AlertSeverity.WARNING, "Supertrend flipped DOWNTREND"))),
        new TransitionSignalDef("bb", "trend", AlertCategory.BOLLINGER,
            IndicatorAlertSnapshot::bbTrendSignal, Map.of(
                "TRENDING",      new SignalVariant("trending",      AlertSeverity.INFO, "BB Squeeze breakout"),
                "CONSOLIDATING", new SignalVariant("consolidating", AlertSeverity.INFO, "BB entering consolidation"))),
        new TransitionSignalDef("vwap", "cross", AlertCategory.VWAP_CROSS,
            IndicatorAlertSnapshot::vwapPosition, Map.of(
                "ABOVE", new SignalVariant("above", AlertSeverity.INFO,    "Price crossed above VWAP"),
                "BELOW", new SignalVariant("below", AlertSeverity.WARNING, "Price crossed below VWAP"))),
        new TransitionSignalDef("df", null, AlertCategory.DELTA_FLOW,
            IndicatorAlertSnapshot::deltaFlowBias, Map.of(
                "BUYING",  new SignalVariant("buying",  AlertSeverity.INFO,    "Delta Flow shifted to BUYING"),
                "SELLING", new SignalVariant("selling", AlertSeverity.WARNING, "Delta Flow shifted to SELLING"))),
        new TransitionSignalDef("chk", null, AlertCategory.CHAIKIN,
            IndicatorAlertSnapshot::chaikinCrossover, Map.of(
                "BULLISH_CROSS", new SignalVariant("bullish", AlertSeverity.INFO,    "Chaikin Oscillator Bullish Cross"),
                "BEARISH_CROSS", new SignalVariant("bearish", AlertSeverity.WARNING, "Chaikin Oscillator Bearish Cross")))
    );

    private record MtfLevelDef(
        String label,
        String qualifier,
        Function<IndicatorAlertSnapshot, BigDecimal> highExtractor,
        Function<IndicatorAlertSnapshot, BigDecimal> lowExtractor
    ) {}

    private static final List<MtfLevelDef> MTF_LEVEL_DEFS = List.of(
        new MtfLevelDef("1d", "1d:range", IndicatorAlertSnapshot::mtfDailyHigh,   IndicatorAlertSnapshot::mtfDailyLow),
        new MtfLevelDef("1w", "1w:range", IndicatorAlertSnapshot::mtfWeeklyHigh,  IndicatorAlertSnapshot::mtfWeeklyLow),
        new MtfLevelDef("1M", "1M:range", IndicatorAlertSnapshot::mtfMonthlyHigh, IndicatorAlertSnapshot::mtfMonthlyLow)
    );

    private static String deriveMtfPosition(BigDecimal close, BigDecimal high, BigDecimal low) {
        if (close.compareTo(high) > 0) return "ABOVE_HIGH";
        if (close.compareTo(low) < 0) return "BELOW_LOW";
        return "INSIDE";
    }

    private void persistSave(EvalKey key, String signal) {
        try {
            stateStore.save(serializeEvalKey(key), signal);
        } catch (Exception e) {
            log.debug("Failed to persist alert state for {}: {}", key, e.getMessage());
        }
    }

    private void persistRemove(EvalKey key) {
        try {
            stateStore.remove(serializeEvalKey(key));
        } catch (Exception e) {
            log.debug("Failed to remove alert state for {}: {}", key, e.getMessage());
        }
    }
}
