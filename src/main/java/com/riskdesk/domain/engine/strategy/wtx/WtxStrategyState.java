package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-instrument runtime state of the WTX strategy.
 * Persisted between candle evaluations via WtxStrategyStatePort.
 */
public record WtxStrategyState(
        String instrument,
        WtxPosition currentPosition,
        /** Entry price of the open position, null when FLAT */
        BigDecimal entryPrice,
        /** Open quantity (contracts), 0 when FLAT */
        BigDecimal entryQty,
        /** Virtual equity at the start of the current NY trading day */
        BigDecimal dayStartEquity,
        /** Running virtual equity (dayStartEquity + realized P&L for the day) */
        BigDecimal currentEquity,
        /** Realized P&L accumulated since dayStart */
        BigDecimal dailyRealizedPnl,
        boolean maxLossHit,
        Instant lastCandleTs,
        Instant updatedAt,
        /** Active profile gating which filters apply. Default BASELINE preserves legacy behaviour. */
        WtxProfile activeProfile,
        /** True when the user has opted-in to routing this instrument's WTX actions to IBKR. */
        boolean autoExecutionEnabled,
        /** ATR snapshotted at entry — used to size the initial stop and trailing buffer. Null when FLAT. */
        BigDecimal entryAtr,
        /** Maximum favorable excursion since entry — best HIGH for LONG, best LOW for SHORT. Null when FLAT. */
        BigDecimal bestFavorablePrice,
        /** Most recently computed trailing-stop price. Null when FLAT or trailing not yet armed. */
        BigDecimal trailingStopPrice
) {
    public static WtxStrategyState initial(String instrument, BigDecimal startEquity) {
        return new WtxStrategyState(
                instrument,
                WtxPosition.FLAT,
                null, BigDecimal.ZERO,
                startEquity, startEquity,
                BigDecimal.ZERO,
                false,
                null,
                Instant.now(),
                WtxProfile.BASELINE,
                false,
                null, null, null
        );
    }

    public WtxStrategyState withPosition(WtxPosition pos, BigDecimal entryPrice, BigDecimal qty) {
        return new WtxStrategyState(instrument, pos, entryPrice, qty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, entryPrice, null);
    }

    public WtxStrategyState withPosition(WtxPosition pos, BigDecimal entryPrice, BigDecimal qty, BigDecimal entryAtr) {
        return new WtxStrategyState(instrument, pos, entryPrice, qty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, entryPrice, null);
    }

    public WtxStrategyState withFlat(BigDecimal realizedPnlAdd) {
        BigDecimal newRealized = dailyRealizedPnl.add(realizedPnlAdd);
        BigDecimal newEquity = dayStartEquity.add(newRealized);
        return new WtxStrategyState(instrument, WtxPosition.FLAT, null, BigDecimal.ZERO,
                dayStartEquity, newEquity, newRealized, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                null, null, null);
    }

    public WtxStrategyState withDayReset(BigDecimal newStartEquity) {
        return new WtxStrategyState(instrument, currentPosition, entryPrice, entryQty,
                newStartEquity, newStartEquity, BigDecimal.ZERO, false, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice);
    }

    public WtxStrategyState withMaxLossHit() {
        return new WtxStrategyState(instrument, WtxPosition.FLAT, null, BigDecimal.ZERO,
                dayStartEquity, currentEquity, dailyRealizedPnl, true, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                null, null, null);
    }

    public WtxStrategyState withLastCandleTs(Instant ts) {
        return new WtxStrategyState(instrument, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, ts, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice);
    }

    public WtxStrategyState withProfile(WtxProfile profile) {
        return new WtxStrategyState(instrument, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                profile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice);
    }

    public WtxStrategyState withAutoExecution(boolean enabled) {
        return new WtxStrategyState(instrument, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, enabled,
                entryAtr, bestFavorablePrice, trailingStopPrice);
    }

    public WtxStrategyState withTrailing(BigDecimal bestFavorablePrice, BigDecimal trailingStopPrice) {
        return new WtxStrategyState(instrument, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice);
    }

    /** Daily P&L = current equity - day start equity */
    public BigDecimal dailyPnl() {
        return currentEquity.subtract(dayStartEquity);
    }
}
