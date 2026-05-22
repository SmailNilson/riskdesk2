package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-(instrument, timeframe) runtime state of the autonomous Playbook strategy.
 */
public record PlaybookStrategyState(
        String instrument,
        String timeframe,
        WtxPosition currentPosition,
        BigDecimal entryPrice,
        BigDecimal entryQty,
        BigDecimal dayStartEquity,
        BigDecimal currentEquity,
        BigDecimal dailyRealizedPnl,
        boolean maxLossHit,
        Instant lastCandleTs,
        Instant updatedAt,
        PlaybookProfile activeProfile,
        boolean autoExecutionEnabled,
        BigDecimal entryAtr,
        BigDecimal bestFavorablePrice,
        BigDecimal trailingStopPrice,
        int configuredOrderQty
) {
    public static final int DEFAULT_ORDER_QTY = 2;

    public static PlaybookStrategyState initial(String instrument, String timeframe, BigDecimal startEquity) {
        return new PlaybookStrategyState(
                instrument,
                timeframe,
                WtxPosition.FLAT,
                null, BigDecimal.ZERO,
                startEquity, startEquity,
                BigDecimal.ZERO,
                false,
                null,
                Instant.now(),
                PlaybookProfile.BASELINE,
                false,
                null, null, null,
                DEFAULT_ORDER_QTY
        );
    }

    public PlaybookStrategyState withPosition(WtxPosition pos, BigDecimal entryPrice, BigDecimal qty, BigDecimal entryAtr) {
        return new PlaybookStrategyState(instrument, timeframe, pos, entryPrice, qty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, entryPrice, null,
                configuredOrderQty);
    }

    public PlaybookStrategyState withFlat(BigDecimal realizedPnlAdd) {
        BigDecimal newRealized = dailyRealizedPnl.add(realizedPnlAdd);
        BigDecimal newEquity = dayStartEquity.add(newRealized);
        return new PlaybookStrategyState(instrument, timeframe, WtxPosition.FLAT, null, BigDecimal.ZERO,
                dayStartEquity, newEquity, newRealized, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                null, null, null,
                configuredOrderQty);
    }

    public PlaybookStrategyState withTrailing(BigDecimal bestPrice, BigDecimal trailingStop) {
        return new PlaybookStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestPrice, trailingStop,
                configuredOrderQty);
    }

    public PlaybookStrategyState withDayReset(BigDecimal newDayStartEquity) {
        return new PlaybookStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                newDayStartEquity, newDayStartEquity, BigDecimal.ZERO,
                false, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                configuredOrderQty);
    }

    public PlaybookStrategyState withProfile(PlaybookProfile profile) {
        return new PlaybookStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                profile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                configuredOrderQty);
    }

    public PlaybookStrategyState withAutoExecution(boolean enabled) {
        return new PlaybookStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, enabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                configuredOrderQty);
    }

    public PlaybookStrategyState withConfiguredOrderQty(int qty) {
        int sanitized = Math.max(1, qty);
        return new PlaybookStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                sanitized);
    }

    public PlaybookStrategyState withMaxLossHit() {
        return new PlaybookStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, true, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                configuredOrderQty);
    }

    public PlaybookStrategyState withLastCandleTs(Instant ts) {
        return new PlaybookStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, ts, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                configuredOrderQty);
    }

    public BigDecimal dailyPnl() {
        return dailyRealizedPnl;
    }
}
