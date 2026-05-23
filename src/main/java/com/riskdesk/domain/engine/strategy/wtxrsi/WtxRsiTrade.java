package com.riskdesk.domain.engine.strategy.wtxrsi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Closed-trade ledger entry. Produced by the backtest engine; can also be
 * persisted by the live executor once execution reporting is wired.
 */
public record WtxRsiTrade(
        WtxRsiSignal.Side side,
        Instant entryTime,
        BigDecimal entryPrice,
        Instant exitTime,
        BigDecimal exitPrice,
        int contracts,
        BigDecimal stopLossFinal,
        BigDecimal takeProfit,        // nullable
        WtxRsiTradeOutcome outcome,
        BigDecimal pnlPoints,
        BigDecimal pnlUsd
) {}
