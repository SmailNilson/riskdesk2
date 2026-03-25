package com.riskdesk.domain.alert.service;

import com.riskdesk.domain.alert.model.*;
import com.riskdesk.presentation.dto.IndicatorSnapshot;
import com.riskdesk.domain.model.Instrument;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates indicator snapshots and produces domain Alert objects.
 * Extracted from the monolithic AlertService.checkIndicators() method.
 */
public class IndicatorAlertEvaluator {

    public List<Alert> evaluate(Instrument instrument, String timeframe, IndicatorSnapshot snap) {
        List<Alert> alerts = new ArrayList<>();
        if (snap == null) return alerts;
        String tf = instrument.name() + ":" + timeframe;

        // EMA crossover
        if ("GOLDEN_CROSS".equals(snap.emaCrossover())) {
            alerts.add(new Alert("ema:golden:" + tf, AlertSeverity.INFO,
                instrument.getDisplayName() + " [" + timeframe + "] — EMA Golden Cross (9 x 50)",
                AlertCategory.EMA, instrument.name()));
        } else if ("DEATH_CROSS".equals(snap.emaCrossover())) {
            alerts.add(new Alert("ema:death:" + tf, AlertSeverity.WARNING,
                instrument.getDisplayName() + " [" + timeframe + "] — EMA Death Cross (9 x 50)",
                AlertCategory.EMA, instrument.name()));
        }

        // RSI extremes
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

        // MACD crossover
        if ("BULLISH_CROSS".equals(snap.macdCrossover())) {
            alerts.add(new Alert("macd:bullish:" + tf, AlertSeverity.INFO,
                instrument.getDisplayName() + " [" + timeframe + "] — MACD Bullish Cross",
                AlertCategory.MACD, instrument.name()));
        } else if ("BEARISH_CROSS".equals(snap.macdCrossover())) {
            alerts.add(new Alert("macd:bearish:" + tf, AlertSeverity.INFO,
                instrument.getDisplayName() + " [" + timeframe + "] — MACD Bearish Cross",
                AlertCategory.MACD, instrument.name()));
        }

        // SMC
        if (snap.lastBreakType() != null) {
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

        // WaveTrend
        if (snap.wtSignal() != null && snap.wtWt1() != null) {
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

        // Order Block touch proxy using VWAP
        if (snap.activeOrderBlocks() != null && !snap.activeOrderBlocks().isEmpty() && snap.vwap() != null) {
            BigDecimal vwap = snap.vwap();
            snap.activeOrderBlocks().forEach(ob -> {
                if (vwap.compareTo(ob.low()) >= 0 && vwap.compareTo(ob.high()) <= 0) {
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
