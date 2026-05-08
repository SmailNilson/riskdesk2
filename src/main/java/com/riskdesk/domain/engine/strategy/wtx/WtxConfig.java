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
        int closeBeforeMin
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
                true, 16, 40, 12
        );
    }

    public int nyCloseLimit() {
        return nySessionEndHour * 60 + nySessionEndMin - closeBeforeMin;
    }

    public int nySessionEndMinutes() {
        return nySessionEndHour * 60 + nySessionEndMin;
    }
}
