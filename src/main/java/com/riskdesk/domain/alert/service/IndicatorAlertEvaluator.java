package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.*;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates indicator snapshots and produces domain Alert objects.
 * Uses transition detection: alerts fire only when a condition CHANGES,
 * not when it persists across polling cycles.
 */
public class IndicatorAlertEvaluator {

    /**
     * Tracks the last known state for each indicator per instrument+timeframe.
     * Key format: "INDICATOR:INSTRUMENT:TIMEFRAME" → last signal value.
     */
    private final Map<String, String> lastState = new ConcurrentHashMap<>();

    /**
     * Rule 4 — Candle Close Guard.
     * Tracks the last candle timestamp on which each SMC / WaveTrend signal fired.
     * Prevents re-firing on the same open candle due to intra-bar repainting.
     * Key format: same as lastState. Value: candle open timestamp.
     */
    private final Map<String, Instant> lastFiredCandle = new ConcurrentHashMap<>();

    /**
     * Rule 4: Returns true if this is a new candle (different from the last candle on which this
     * signal fired), and records the current candle timestamp. Returns true if no timestamp is
     * available (fail-open). Used for SMC and WaveTrend to prevent intra-bar repainting noise.
     */
    private boolean canFireOnCandle(String candleKey, Instant lastCandleTimestamp) {
        if (lastCandleTimestamp == null) return true;
        Instant prev = lastFiredCandle.get(candleKey);
        if (lastCandleTimestamp.equals(prev)) return false;
        lastFiredCandle.put(candleKey, lastCandleTimestamp);
        return true;
    }

    /**
     * Returns true if the signal is new (different from last known state)
     * and updates the tracking map. Returns false if signal is unchanged or null.
     */
    private boolean isTransition(String stateKey, String currentSignal) {
        if (currentSignal == null) {
            lastState.remove(stateKey);
            return false;
        }
        String previous = lastState.put(stateKey, currentSignal);
        return !currentSignal.equals(previous);
    }

    public List<Alert> evaluate(Instrument instrument, String timeframe, IndicatorAlertSnapshot snap) {
        List<Alert> alerts = new ArrayList<>();
        if (snap == null) return alerts;
        String tf = instrument.name() + ":" + timeframe;

        // EMA crossover — only on transition
        String emaKey = "ema:" + tf;
        if (isTransition(emaKey, snap.emaCrossover())) {
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

        // RSI extremes — only on transition
        String rsiKey = "rsi:" + tf;
        if (isTransition(rsiKey, snap.rsiSignal())) {
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

        // MACD crossover — only on transition
        String macdKey = "macd:" + tf;
        if (isTransition(macdKey, snap.macdCrossover())) {
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
        String smcIntKey = "smc:internal:" + tf;
        if (isTransition(smcIntKey, snap.lastInternalBreakType())
                && snap.lastInternalBreakType() != null
                && canFireOnCandle(smcIntKey + ":candle", snap.lastCandleTimestamp())) {
            if (snap.lastInternalBreakType().startsWith("CHOCH")) {
                alerts.add(new Alert("smc:internal:choch:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — Internal CHoCH: " + snap.lastInternalBreakType(),
                    AlertCategory.SMC, instrument.name()));
            } else if (snap.lastInternalBreakType().startsWith("BOS")) {
                alerts.add(new Alert("smc:internal:bos:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — Internal BOS: " + snap.lastInternalBreakType(),
                    AlertCategory.SMC, instrument.name()));
            }
        }

        // SMC Swing — only on transition AND on a new candle (Rule 4: candle close guard)
        String smcSwKey = "smc:swing:" + tf;
        if (isTransition(smcSwKey, snap.lastSwingBreakType())
                && snap.lastSwingBreakType() != null
                && canFireOnCandle(smcSwKey + ":candle", snap.lastCandleTimestamp())) {
            if (snap.lastSwingBreakType().startsWith("CHOCH")) {
                alerts.add(new Alert("smc:swing:choch:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — Swing CHoCH: " + snap.lastSwingBreakType(),
                    AlertCategory.SMC, instrument.name()));
            } else if (snap.lastSwingBreakType().startsWith("BOS")) {
                alerts.add(new Alert("smc:swing:bos:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — Swing BOS: " + snap.lastSwingBreakType(),
                    AlertCategory.SMC, instrument.name()));
            }
        }

        // SMC legacy — kept for backward compat (fires on either level transition)
        String smcKey = "smc:" + tf;
        if (isTransition(smcKey, snap.lastBreakType())
                && snap.lastBreakType() != null
                && canFireOnCandle(smcKey + ":candle", snap.lastCandleTimestamp())) {
            if (snap.lastBreakType().startsWith("CHOCH")) {
                alerts.add(new Alert("smc:choch:" + tf, AlertSeverity.WARNING,
                    instrument.getDisplayName() + " [" + timeframe + "] — CHoCH detected: " + snap.lastBreakType(),
                    AlertCategory.SMC, instrument.name()));
            } else if (snap.lastBreakType().startsWith("BOS")) {
                alerts.add(new Alert("smc:bos:" + tf, AlertSeverity.INFO,
                    instrument.getDisplayName() + " [" + timeframe + "] — BOS: " + snap.lastBreakType(),
                    AlertCategory.SMC, instrument.name()));
            }
        }

        // WaveTrend signal — only on transition AND on a new candle (Rule 4: candle close guard)
        if (snap.wtWt1() != null) {
            String wtSignalKey = "wt:signal:" + tf;
            if (isTransition(wtSignalKey, snap.wtSignal())
                    && snap.wtSignal() != null
                    && canFireOnCandle(wtSignalKey + ":candle", snap.lastCandleTimestamp())) {
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
            String wtCrossKey = "wt:cross:" + tf;
            if (isTransition(wtCrossKey, snap.wtCrossover())
                    && snap.wtCrossover() != null
                    && canFireOnCandle(wtCrossKey + ":candle", snap.lastCandleTimestamp())) {
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

        // Order Block touch proxy using VWAP — only on transition per OB type
        if (snap.activeOrderBlocks() != null && !snap.activeOrderBlocks().isEmpty() && snap.vwap() != null) {
            BigDecimal vwap = snap.vwap();
            snap.activeOrderBlocks().forEach(ob -> {
                boolean inside = vwap.compareTo(ob.low()) >= 0 && vwap.compareTo(ob.high()) <= 0;
                String obKey = "ob:" + ob.type() + ":" + tf;
                String obSignal = inside ? "INSIDE" : null;
                if (isTransition(obKey, obSignal)) {
                    alerts.add(new Alert("ob:touch:" + ob.type() + ":" + tf, AlertSeverity.INFO,
                        String.format("%s [%s] — VWAP inside %s Order Block [%.5f – %.5f]",
                            instrument.getDisplayName(), timeframe, ob.type(),
                            ob.low().doubleValue(), ob.high().doubleValue()),
                        AlertCategory.ORDER_BLOCK, instrument.name()));
                }
            });
        }

        return alerts;
    }
}
