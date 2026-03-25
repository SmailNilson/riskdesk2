package com.riskdesk.application.service;

import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.AlertCategory;
import com.riskdesk.domain.alert.model.AlertSeverity;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Listens for 1h candle closes on MNQ and evaluates the WT_X strategy.
 * When a crossover occurs in OB/OS zones, fires a SIGNAL alert.
 *
 * Strategy rules (matching WT_X PineScript):
 *   LONG  = WT1 crosses above WT2 AND WT1 <= nSV (-53)  [oversold zone]
 *   SHORT = WT1 crosses below WT2 AND WT1 >= nSC (53)   [overbought zone]
 */
@Service
public class WaveTrendSignalScanner {

    private static final Logger log = LoggerFactory.getLogger(WaveTrendSignalScanner.class);

    private static final int N1 = 10;
    private static final int N2 = 21;
    private static final double NSC = 53;   // overbought threshold
    private static final double NSV = -53;  // oversold threshold
    private static final int MIN_CANDLES = 50;

    private final CandleRepositoryPort candlePort;
    private final AlertService alertService;

    public WaveTrendSignalScanner(CandleRepositoryPort candlePort, AlertService alertService) {
        this.candlePort = candlePort;
        this.alertService = alertService;
    }

    @EventListener
    public void onCandleClosed(CandleClosed event) {
        // Only scan MNQ on 1h timeframe
        if (!"MNQ".equals(event.instrument()) || !"1h".equals(event.timeframe())) {
            return;
        }

        try {
            scan(Instrument.MNQ, event.timestamp());
        } catch (Exception e) {
            log.error("WT signal scan failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual trigger for testing — called from BacktestController or REST endpoint.
     */
    public void scanNow() {
        log.info("Manual WT signal scan triggered");
        scan(Instrument.MNQ, Instant.now());
    }

    private void scan(Instrument instrument, Instant candleTime) {
        List<Candle> candles = candlePort.findCandles(instrument, "1h",
                Instant.now().minus(30, ChronoUnit.DAYS));

        if (candles.size() < MIN_CANDLES) {
            log.debug("WT scanner: only {} candles for MNQ 1h, need {}", candles.size(), MIN_CANDLES);
            return;
        }

        int n = candles.size();
        double[] hlc3 = new double[n];
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            hlc3[i] = (c.getHigh().doubleValue() + c.getLow().doubleValue() + c.getClose().doubleValue()) / 3.0;
        }

        // ESA = EMA(hlc3, n1)
        double[] esa = ema(hlc3, N1);

        // d = EMA(|hlc3 - esa|, n1)
        double[] dev = new double[n];
        for (int i = 0; i < n; i++) dev[i] = Math.abs(hlc3[i] - esa[i]);
        double[] d = ema(dev, N1);

        // CI = (hlc3 - esa) / (0.015 * d)
        double[] ci = new double[n];
        for (int i = 0; i < n; i++) {
            ci[i] = d[i] == 0 ? 0 : (hlc3[i] - esa[i]) / (0.015 * d[i]);
        }

        // WT1 = EMA(CI, n2), WT2 = SMA(WT1, 4)
        double[] wt1 = ema(ci, N2);
        double[] wt2 = sma(wt1, 4);

        // Check the LAST closed candle for a crossover signal
        int last = n - 1;
        if (last < 1) return;

        double prevWt1 = wt1[last - 1], prevWt2 = wt2[last - 1];
        double currWt1 = wt1[last],      currWt2 = wt2[last];

        boolean crossOver  = prevWt1 <= prevWt2 && currWt1 > currWt2;
        boolean crossUnder = prevWt1 >= prevWt2 && currWt1 < currWt2;

        Candle lastCandle = candles.get(last);
        double closePrice = lastCandle.getClose().doubleValue();

        log.info("WT scan MNQ 1h — WT1={} WT2={} crossOver={} crossUnder={} price={}",
                String.format("%.2f", currWt1), String.format("%.2f", currWt2), crossOver, crossUnder, closePrice);

        if (crossOver && currWt1 <= NSV) {
            // LONG signal — crossover in oversold zone
            String msg = String.format(
                    "SIGNAL BUY MNQ @ %.2f — WaveTrend bullish cross in oversold zone (WT1=%.1f, WT2=%.1f)",
                    closePrice, currWt1, currWt2);
            Alert alert = new Alert("signal:long:MNQ:1h", AlertSeverity.DANGER, msg,
                    AlertCategory.SIGNAL, "MNQ");
            alertService.publish(alert);
            log.info(">>> {}", msg);

        } else if (crossUnder && currWt1 >= NSC) {
            // SHORT signal — crossunder in overbought zone
            String msg = String.format(
                    "SIGNAL SELL MNQ @ %.2f — WaveTrend bearish cross in overbought zone (WT1=%.1f, WT2=%.1f)",
                    closePrice, currWt1, currWt2);
            Alert alert = new Alert("signal:short:MNQ:1h", AlertSeverity.DANGER, msg,
                    AlertCategory.SIGNAL, "MNQ");
            alertService.publish(alert);
            log.info(">>> {}", msg);
        }
    }

    private static double[] ema(double[] src, int period) {
        double[] out = new double[src.length];
        double mult = 2.0 / (period + 1);
        out[0] = src[0];
        for (int i = 1; i < src.length; i++) {
            out[i] = (src[i] - out[i - 1]) * mult + out[i - 1];
        }
        return out;
    }

    private static double[] sma(double[] src, int period) {
        double[] out = new double[src.length];
        double sum = 0;
        for (int i = 0; i < src.length; i++) {
            sum += src[i];
            if (i >= period) sum -= src[i - period];
            out[i] = i < period - 1 ? src[i] : sum / Math.min(i + 1, period);
        }
        return out;
    }
}
