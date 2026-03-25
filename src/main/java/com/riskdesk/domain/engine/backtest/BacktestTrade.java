package com.riskdesk.domain.engine.backtest;

import java.time.Instant;

public record BacktestTrade(
    int tradeNo,
    String side,           // "LONG" or "SHORT"
    double entryPrice,
    Instant entryTime,
    double exitPrice,
    Instant exitTime,
    double qty,
    double pnl,
    double pnlPct,
    String exitReason      // "SIGNAL_REVERSE", "CLOSE_SIGNAL", "END_OF_DATA"
) {}
