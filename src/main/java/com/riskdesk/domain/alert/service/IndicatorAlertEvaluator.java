package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.alert.model.IndicatorAlertSnapshot;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates indicator snapshots and produces domain Alert objects.
 * Uses transition detection: alerts fire only when a condition CHANGES,
 * not when it persists across polling cycles.
 */
public class IndicatorAlertEvaluator {

    private static final int MAX_ORDER_BLOCK_EVENT_KEYS = 5_000;

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
     */
    private boolean isTransition(EvalKey stateKey, String currentSignal) {
        if (currentSignal == null) {
            lastState.remove(stateKey);
            return false;
        }
        String previous = lastState.put(stateKey, currentSignal);
        return !currentSignal.equals(previous);
    }

    /**
     * For candle-guarded signals, only commit the new state after the candle check passes.
     * This avoids consuming an intra-bar transition that is later rejected as unconfirmed.
     */
    private boolean isConfirmedTransition(EvalKey stateKey, String currentSignal,
                                          EvalKey candleKey, Instant lastCandleTimestamp) {
        if (currentSignal == null) {
            lastState.remove(stateKey);
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

        // ── V2 signal blocks ─────────────────────────────────────────────────

        // 1. Supertrend — transition on supertrendDirection
        EvalKey stKey = key("st", instrument, timeframe, null);
        if (isConfirmedTransition(stKey, snap.supertrendDirection(), candleKey(stKey), snap.lastCandleTimestamp())) {
            if ("UPTREND".equals(snap.supertrendDirection())) {
                alerts.add(new Alert("st:up:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — Supertrend flipped UPTREND",
                    AlertCategory.SUPERTREND, instrument.name()));
            } else if ("DOWNTREND".equals(snap.supertrendDirection())) {
                alerts.add(new Alert("st:down:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — Supertrend flipped DOWNTREND",
                    AlertCategory.SUPERTREND, instrument.name()));
            }
        }

        // 2. BB Squeeze — transition on bbTrendSignal
        EvalKey bbKey = key("bb", instrument, timeframe, "trend");
        if (isConfirmedTransition(bbKey, snap.bbTrendSignal(), candleKey(bbKey), snap.lastCandleTimestamp())) {
            if ("TRENDING".equals(snap.bbTrendSignal())) {
                alerts.add(new Alert("bb:trending:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — BB Squeeze breakout",
                    AlertCategory.BOLLINGER, instrument.name()));
            } else if ("CONSOLIDATING".equals(snap.bbTrendSignal())) {
                alerts.add(new Alert("bb:consolidating:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — BB entering consolidation",
                    AlertCategory.BOLLINGER, instrument.name()));
            }
        }

        // 3. VWAP Cross — transition on vwapPosition
        EvalKey vwapKey = key("vwap", instrument, timeframe, "cross");
        if (isConfirmedTransition(vwapKey, snap.vwapPosition(), candleKey(vwapKey), snap.lastCandleTimestamp())) {
            if ("ABOVE".equals(snap.vwapPosition())) {
                alerts.add(new Alert("vwap:above:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — Price crossed above VWAP",
                    AlertCategory.VWAP_CROSS, instrument.name()));
            } else if ("BELOW".equals(snap.vwapPosition())) {
                alerts.add(new Alert("vwap:below:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — Price crossed below VWAP",
                    AlertCategory.VWAP_CROSS, instrument.name()));
            }
        }

        // 4. FVG Events — one-shot events using shouldFireOrderBlockEvent pattern
        if (snap.recentFvgEvents() != null && !snap.recentFvgEvents().isEmpty()) {
            for (IndicatorAlertSnapshot.FvgEvent evt : snap.recentFvgEvents()) {
                EvalKey fvgEventKey = key("fvg", instrument, timeframe, evt.bias(),
                        evt.top(), evt.bottom());
                if (shouldFireOrderBlockEvent(fvgEventKey, snap.lastCandleTimestamp())) {
                    alerts.add(new Alert("fvg:" + evt.bias().toLowerCase() + ":" + tf, AlertSeverity.INFO,
                        String.format("%s [%s] — FVG %s [%.5f – %.5f]",
                            instrument.getDisplayName(), timeframe, evt.bias(),
                            evt.bottom().doubleValue(), evt.top().doubleValue()),
                        AlertCategory.FVG, instrument.name()));
                }
            }
        }

        // 5. EQH/EQL Sweep — one-shot events using shouldFireOrderBlockEvent pattern
        if (snap.recentSweepEvents() != null && !snap.recentSweepEvents().isEmpty()) {
            for (IndicatorAlertSnapshot.SweepEvent evt : snap.recentSweepEvents()) {
                EvalKey sweepEventKey = key("eql", instrument, timeframe, evt.type(),
                        evt.price(), evt.price());
                if (shouldFireOrderBlockEvent(sweepEventKey, snap.lastCandleTimestamp())) {
                    alerts.add(new Alert("eql:" + evt.type().toLowerCase() + ":" + tf, AlertSeverity.WARNING,
                        String.format("%s [%s] — %s sweep at %.5f",
                            instrument.getDisplayName(), timeframe, evt.type(),
                            evt.price().doubleValue()),
                        AlertCategory.EQUAL_LEVEL, instrument.name()));
                }
            }
        }

        // 6. Delta Flow — transition on deltaFlowBias (skip NEUTRAL)
        EvalKey dfKey = key("df", instrument, timeframe, null);
        if (isConfirmedTransition(dfKey, snap.deltaFlowBias(), candleKey(dfKey), snap.lastCandleTimestamp())) {
            if ("BUYING".equals(snap.deltaFlowBias())) {
                alerts.add(new Alert("df:buying:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — Delta Flow shifted to BUYING",
                    AlertCategory.DELTA_FLOW, instrument.name()));
            } else if ("SELLING".equals(snap.deltaFlowBias())) {
                alerts.add(new Alert("df:selling:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — Delta Flow shifted to SELLING",
                    AlertCategory.DELTA_FLOW, instrument.name()));
            }
        }

        // 7. Chaikin — transition on chaikinCrossover
        EvalKey chkKey = key("chk", instrument, timeframe, null);
        if (isConfirmedTransition(chkKey, snap.chaikinCrossover(), candleKey(chkKey), snap.lastCandleTimestamp())) {
            if ("BULLISH_CROSS".equals(snap.chaikinCrossover())) {
                alerts.add(new Alert("chk:bullish:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — Chaikin Oscillator Bullish Cross",
                    AlertCategory.CHAIKIN, instrument.name()));
            } else if ("BEARISH_CROSS".equals(snap.chaikinCrossover())) {
                alerts.add(new Alert("chk:bearish:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — Chaikin Oscillator Bearish Cross",
                    AlertCategory.CHAIKIN, instrument.name()));
            }
        }

        // 8. MTF Level Cross — derive position relative to each MTF level pair, fire on transition
        if (snap.close() != null) {
            // Daily range
            if (snap.mtfDailyHigh() != null && snap.mtfDailyLow() != null) {
                String dailyPos = deriveMtfPosition(snap.close(), snap.mtfDailyHigh(), snap.mtfDailyLow());
                EvalKey mtfDailyKey = key("mtf", instrument, timeframe, "1d:range");
                if (isConfirmedTransition(mtfDailyKey, dailyPos, candleKey(mtfDailyKey), snap.lastCandleTimestamp())) {
                    alerts.add(new Alert("mtf:1d:" + dailyPos.toLowerCase() + ":" + tf, AlertSeverity.INFO,
                        String.format("%s [%s] — Price %s daily range [%.5f – %.5f]",
                            instrument.getDisplayName(), timeframe, dailyPos.replace("_", " ").toLowerCase(),
                            snap.mtfDailyLow().doubleValue(), snap.mtfDailyHigh().doubleValue()),
                        AlertCategory.MTF_LEVEL, instrument.name()));
                }
            }
            // Weekly range
            if (snap.mtfWeeklyHigh() != null && snap.mtfWeeklyLow() != null) {
                String weeklyPos = deriveMtfPosition(snap.close(), snap.mtfWeeklyHigh(), snap.mtfWeeklyLow());
                EvalKey mtfWeeklyKey = key("mtf", instrument, timeframe, "1w:range");
                if (isConfirmedTransition(mtfWeeklyKey, weeklyPos, candleKey(mtfWeeklyKey), snap.lastCandleTimestamp())) {
                    alerts.add(new Alert("mtf:1w:" + weeklyPos.toLowerCase() + ":" + tf, AlertSeverity.INFO,
                        String.format("%s [%s] — Price %s weekly range [%.5f – %.5f]",
                            instrument.getDisplayName(), timeframe, weeklyPos.replace("_", " ").toLowerCase(),
                            snap.mtfWeeklyLow().doubleValue(), snap.mtfWeeklyHigh().doubleValue()),
                        AlertCategory.MTF_LEVEL, instrument.name()));
                }
            }
            // Monthly range
            if (snap.mtfMonthlyHigh() != null && snap.mtfMonthlyLow() != null) {
                String monthlyPos = deriveMtfPosition(snap.close(), snap.mtfMonthlyHigh(), snap.mtfMonthlyLow());
                EvalKey mtfMonthlyKey = key("mtf", instrument, timeframe, "1M:range");
                if (isConfirmedTransition(mtfMonthlyKey, monthlyPos, candleKey(mtfMonthlyKey), snap.lastCandleTimestamp())) {
                    alerts.add(new Alert("mtf:1M:" + monthlyPos.toLowerCase() + ":" + tf, AlertSeverity.INFO,
                        String.format("%s [%s] — Price %s monthly range [%.5f – %.5f]",
                            instrument.getDisplayName(), timeframe, monthlyPos.replace("_", " ").toLowerCase(),
                            snap.mtfMonthlyLow().doubleValue(), snap.mtfMonthlyHigh().doubleValue()),
                        AlertCategory.MTF_LEVEL, instrument.name()));
                }
            }
        }

        return alerts;
    }

    /**
     * Derives the position of close relative to an MTF high/low range.
     * Returns "ABOVE_HIGH" if close > high, "BELOW_LOW" if close < low, "INSIDE" otherwise.
     */
    private static String deriveMtfPosition(BigDecimal close, BigDecimal high, BigDecimal low) {
        if (close.compareTo(high) > 0) return "ABOVE_HIGH";
        if (close.compareTo(low) < 0) return "BELOW_LOW";
        return "INSIDE";
    }
}
