package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;
import java.util.List;

/**
 * Immutable configuration for the WaveTrend XT strategy.
 * Mirrors Pine Script strategy inputs — populated from application.properties
 * by the application layer and injected as a plain record into domain services.
 */
public record WtxConfig(
        List<String> instruments,
        List<String> timeframes,

        // WaveTrend indicator params
        int n1,
        int n2,
        int signalPeriod,
        BigDecimal nsc,  // overbought level (default +53)
        BigDecimal nsv,  // oversold level  (default -53)

        // Signal type flags
        boolean useCompra,
        boolean useCompra1,
        boolean useVenta,
        boolean useVenta1,

        // Position management
        boolean reverseOnOpp,
        BigDecimal fixedQty,

        // Risk guards
        BigDecimal maxDailyLossUsd,

        // NY session close
        boolean forceCloseNy,
        int nySessionEndHour,
        int nySessionEndMin,
        int closeBeforeMin,

        // ATR trailing exits (active when profile >= SESSION_ATR)
        int atrLength,                 // 14 — period used for AtrCalculator
        BigDecimal slAtrMult,          // 1.4 — initial fixed stop distance in ATR (Pine "Stop ATR multiplier")
        BigDecimal tpAtrMult,          // 2.1 — used to derive R unit for activation gate (Pine "Target ATR multiplier")
        BigDecimal trailingAtrMult,    // 2.0 — trailing-stop ATR distance once activated
        BigDecimal trailingActivationR, // 0.5 — favorable move (in R units = slAtrMult*ATR) before trailing arms

        // HTF bias (active when profile >= HTF)
        String htfTimeframe,           // "1h"
        int htfFastLen,                // 21
        int htfSlowLen,                // 55

        // Structure proxy (active when profile == STRICT)
        int structureLookback,         // 12
        BigDecimal sweepBufferAtr      // 0.05
) {
    public static WtxConfig defaults() {
        return new WtxConfig(
                List.of("MNQ", "MCL", "MGC", "6E"),
                List.of("5m", "10m"),
                10, 21, 4,
                BigDecimal.valueOf(53),
                BigDecimal.valueOf(-53),
                true, false, true, false,
                true,
                BigDecimal.valueOf(2.0),
                BigDecimal.valueOf(500.0),
                true, 16, 40, 12,
                14,
                BigDecimal.valueOf(1.4),
                BigDecimal.valueOf(2.1),
                BigDecimal.valueOf(2.0),
                BigDecimal.valueOf(0.5),
                "1h", 21, 55,
                12, BigDecimal.valueOf(0.05)
        );
    }

    public int nyCloseLimit() {
        return nySessionEndHour * 60 + nySessionEndMin - closeBeforeMin;
    }

    public int nySessionEndMinutes() {
        return nySessionEndHour * 60 + nySessionEndMin;
    }
}
