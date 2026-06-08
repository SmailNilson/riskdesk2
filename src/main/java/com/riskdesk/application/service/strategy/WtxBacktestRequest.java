package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.wtx.WtxProfile;
import com.riskdesk.domain.engine.strategy.wtx.WtxTrailingMode;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request for the faithful WTX replay backtest ({@code POST /api/strategy/wtx/backtest}).
 *
 * <p>Loads 1m candles in {@code [from, to]}, resamples to {@code timeframe} (signals) and to the config's
 * HTF timeframe (bias), and replays the live evaluators. The primary use is an A/B of the opt-in take-profit:
 * run once with {@code takeProfitEnabled=false} and once with it {@code true} (+ a {@code tpPoints} sweep)
 * over the SAME window. Nullable overrides fall back to {@code application.properties} defaults.</p>
 */
public record WtxBacktestRequest(
        String instrument,            // e.g. "MNQ"
        String timeframe,             // signal timeframe; default "10m"
        Instant from,
        Instant to,
        WtxProfile profile,           // default HTF (the live edge); BASELINE/SESSION_ATR also valid
        WtxTrailingMode trailingMode, // default SL_ONLY (the validated default)
        Boolean takeProfitEnabled,    // the lever under test (default false)
        BigDecimal tpPoints,          // 0 → dynamic tpAtrMult*ATR
        BigDecimal slAtrMult,         // override the initial-stop ATR multiple
        Integer n1,
        Integer n2,
        Integer signalPeriod,
        Double pointValue,            // $/point (default 2.0 = MNQ)
        Double startEquity            // default = riskdesk.wtx.initial-equity
) {}
