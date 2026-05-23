package com.riskdesk.domain.engine.strategy.wtxrsi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Result of running the WTX+RSI evaluator on one closed candle.
 *
 * {@code confirmed} reflects whether the optional Chaikin oscillator agreed
 * with the side at signal time (drives the contract-size multiplier).
 *
 * The signal is <b>strictly causal</b> — all values come from candles whose
 * close timestamp is ≤ {@code timestamp}.
 */
public record WtxRsiSignal(
        int barIndex,
        Instant timestamp,
        Side side,
        boolean confirmed,
        BigDecimal wt1,
        BigDecimal wt2,
        BigDecimal rsi,
        BigDecimal rsiSma,
        BigDecimal chaikin,
        BigDecimal close
) {
    public enum Side { LONG, SHORT }
}
