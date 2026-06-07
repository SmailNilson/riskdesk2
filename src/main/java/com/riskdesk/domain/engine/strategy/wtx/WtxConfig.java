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
        BigDecimal sweepBufferAtr,     // 0.05

        // Point-based trailing exits (when trailingMode == POINTS, used instead of the
        // ATR distances above for activation + trail). Backtest-tuned arm/trail.
        WtxTrailingMode trailingMode,         // ATR (legacy) | POINTS
        BigDecimal trailingActivationPoints,  // 30 — favorable points before trailing arms (POINTS mode)
        BigDecimal trailingPoints,            // 15 — trail distance in points once armed (POINTS mode)
        BigDecimal slPoints,                  // 0 → use slAtrMult*ATR (dynamic); >0 → fixed point stop

        // Daily-loss latch reset at the CME day boundary (17:00 ET) via WtxDailyResetScheduler.
        boolean dailyResetEnabled,            // true — clear maxLossHit + rebaseline equity at 17:00 ET

        // Instruments POINTS trailing applies to (the 30/15 distances are MNQ-scale; other instruments
        // have very different point scales, so they stay on instrument-relative ATR trailing). Empty = all.
        List<String> trailingPointsInstruments,

        // Session entry filter — blocks NEW entries (OPEN / REVERSE-open) inside a thin-liquidity
        // window. Boundaries are minutes-of-day in America/New_York (DST-safe) and may wrap past
        // midnight (start > end, e.g. 18:00 → 03:00 = Asia/overnight). Disabled by default so the
        // domain defaults() stay bit-for-bit; the live config opts in.
        boolean sessionFilterEnabled,
        int sessionBlockStartMinEt,   // e.g. 18*60 = 1080 (18:00 ET)
        int sessionBlockEndMinEt      // e.g.  3*60 =  180 (03:00 ET)
) {

    /** True when this instrument should use POINTS-mode arm/trail (else ATR). Empty list = all instruments. */
    public boolean usesPointTrailing(String instrument) {
        return trailingMode == WtxTrailingMode.POINTS
                && (trailingPointsInstruments == null
                    || trailingPointsInstruments.isEmpty()
                    || trailingPointsInstruments.contains(instrument));
    }
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
                12, BigDecimal.valueOf(0.05),
                // Legacy ATR trailing by default — keeps existing evaluator tests bit-for-bit.
                // The live config (application.properties) opts into POINTS mode.
                WtxTrailingMode.ATR,
                BigDecimal.valueOf(30), BigDecimal.valueOf(15), BigDecimal.ZERO,
                true,
                List.of("MNQ"),
                // Session filter disabled in domain defaults — keeps evaluator/risk-guard tests
                // bit-for-bit. The live config (application.properties) opts in (18:00 → 03:00 ET).
                false, 0, 0
        );
    }

    /**
     * True when the session entry filter is enabled and {@code nyMinutes} (minutes-of-day in
     * America/New_York) falls inside the blocked window. Handles a window that wraps past midnight
     * (start &gt; end, e.g. 18:00 → 03:00). Block is half-open {@code [start, end)} on the wrap so
     * the 03:00 bar itself is tradeable again.
     */
    public boolean isWithinSessionBlock(int nyMinutes) {
        if (!sessionFilterEnabled) return false;
        if (sessionBlockStartMinEt == sessionBlockEndMinEt) return false; // empty window = no block
        if (sessionBlockStartMinEt < sessionBlockEndMinEt) {
            return nyMinutes >= sessionBlockStartMinEt && nyMinutes < sessionBlockEndMinEt;
        }
        // wrap past midnight: blocked if at/after start OR before end
        return nyMinutes >= sessionBlockStartMinEt || nyMinutes < sessionBlockEndMinEt;
    }

    /**
     * Copy with point-based trailing distances and an explicit stop-loss point distance
     * ({@code slPts <= 0} keeps the dynamic {@code slAtrMult*ATR} stop). All other fields
     * are preserved — convenience for tests and config wiring.
     */
    public WtxConfig withTrailing(WtxTrailingMode mode, BigDecimal activationPts,
                                  BigDecimal trailPts, BigDecimal slPts) {
        return new WtxConfig(
                instruments, timeframes, n1, n2, signalPeriod, nsc, nsv,
                useCompra, useCompra1, useVenta, useVenta1, reverseOnOpp, fixedQty,
                maxDailyLossUsd, forceCloseNy, nySessionEndHour, nySessionEndMin, closeBeforeMin,
                atrLength, slAtrMult, tpAtrMult, trailingAtrMult, trailingActivationR,
                htfTimeframe, htfFastLen, htfSlowLen, structureLookback, sweepBufferAtr,
                mode, activationPts, trailPts, slPts, dailyResetEnabled,
                // Wither scopes point trailing to ALL instruments (empty) — convenience for tests/config.
                List.of(),
                sessionFilterEnabled, sessionBlockStartMinEt, sessionBlockEndMinEt);
    }

    /** Copy scoping POINTS trailing to the given instruments (empty = all). Preserves all other fields. */
    public WtxConfig withPointTrailingInstruments(List<String> pointsInstruments) {
        return new WtxConfig(
                instruments, timeframes, n1, n2, signalPeriod, nsc, nsv,
                useCompra, useCompra1, useVenta, useVenta1, reverseOnOpp, fixedQty,
                maxDailyLossUsd, forceCloseNy, nySessionEndHour, nySessionEndMin, closeBeforeMin,
                atrLength, slAtrMult, tpAtrMult, trailingAtrMult, trailingActivationR,
                htfTimeframe, htfFastLen, htfSlowLen, structureLookback, sweepBufferAtr,
                trailingMode, trailingActivationPoints, trailingPoints, slPoints, dailyResetEnabled,
                pointsInstruments,
                sessionFilterEnabled, sessionBlockStartMinEt, sessionBlockEndMinEt);
    }

    /** Copy enabling/disabling the session entry filter with explicit ET minute boundaries. Preserves all other fields. */
    public WtxConfig withSessionFilter(boolean enabled, int blockStartMinEt, int blockEndMinEt) {
        return new WtxConfig(
                instruments, timeframes, n1, n2, signalPeriod, nsc, nsv,
                useCompra, useCompra1, useVenta, useVenta1, reverseOnOpp, fixedQty,
                maxDailyLossUsd, forceCloseNy, nySessionEndHour, nySessionEndMin, closeBeforeMin,
                atrLength, slAtrMult, tpAtrMult, trailingAtrMult, trailingActivationR,
                htfTimeframe, htfFastLen, htfSlowLen, structureLookback, sweepBufferAtr,
                trailingMode, trailingActivationPoints, trailingPoints, slPoints, dailyResetEnabled,
                trailingPointsInstruments, enabled, blockStartMinEt, blockEndMinEt);
    }

    /**
     * Copy with overridden WaveTrend periods (n1 = channel, n2 = average, signalPeriod = signal SMA).
     * Used to apply per-(instrument,timeframe) frontend overrides on top of the global config.
     * Preserves all other fields.
     */
    public WtxConfig withIndicatorParams(int newN1, int newN2, int newSignalPeriod) {
        return new WtxConfig(
                instruments, timeframes, newN1, newN2, newSignalPeriod, nsc, nsv,
                useCompra, useCompra1, useVenta, useVenta1, reverseOnOpp, fixedQty,
                maxDailyLossUsd, forceCloseNy, nySessionEndHour, nySessionEndMin, closeBeforeMin,
                atrLength, slAtrMult, tpAtrMult, trailingAtrMult, trailingActivationR,
                htfTimeframe, htfFastLen, htfSlowLen, structureLookback, sweepBufferAtr,
                trailingMode, trailingActivationPoints, trailingPoints, slPoints, dailyResetEnabled,
                trailingPointsInstruments, sessionFilterEnabled, sessionBlockStartMinEt, sessionBlockEndMinEt);
    }

    /**
     * Copy with an overridden initial-stop ATR multiple. Used to apply a per-(instrument,timeframe)
     * frontend SL override on top of the global config. Preserves all other fields.
     */
    public WtxConfig withSlAtrMult(BigDecimal newSlAtrMult) {
        return new WtxConfig(
                instruments, timeframes, n1, n2, signalPeriod, nsc, nsv,
                useCompra, useCompra1, useVenta, useVenta1, reverseOnOpp, fixedQty,
                maxDailyLossUsd, forceCloseNy, nySessionEndHour, nySessionEndMin, closeBeforeMin,
                atrLength, newSlAtrMult, tpAtrMult, trailingAtrMult, trailingActivationR,
                htfTimeframe, htfFastLen, htfSlowLen, structureLookback, sweepBufferAtr,
                trailingMode, trailingActivationPoints, trailingPoints, slPoints, dailyResetEnabled,
                trailingPointsInstruments, sessionFilterEnabled, sessionBlockStartMinEt, sessionBlockEndMinEt);
    }

    public int nyCloseLimit() {
        return nySessionEndHour * 60 + nySessionEndMin - closeBeforeMin;
    }

    public int nySessionEndMinutes() {
        return nySessionEndHour * 60 + nySessionEndMin;
    }
}
