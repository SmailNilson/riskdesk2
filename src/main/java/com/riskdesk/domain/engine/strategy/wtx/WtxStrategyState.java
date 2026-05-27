package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-(instrument, timeframe) runtime state of the WTX strategy.
 * Each timeframe carries its own independent position, equity, profile and
 * auto-execution toggle — a 5m position must never block or thrash a 10m signal.
 * Persisted between candle evaluations via WtxStrategyStatePort.
 */
public record WtxStrategyState(
        String instrument,
        String timeframe,
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
        /** True when the user has opted-in to routing this timeframe's WTX actions to IBKR. */
        boolean autoExecutionEnabled,
        /** ATR snapshotted at entry — used to size the initial stop and trailing buffer. Null when FLAT. */
        BigDecimal entryAtr,
        /** Maximum favorable excursion since entry — best HIGH for LONG, best LOW for SHORT. Null when FLAT. */
        BigDecimal bestFavorablePrice,
        /** Most recently computed trailing-stop price. Null when FLAT or trailing not yet armed. */
        BigDecimal trailingStopPrice,
        /**
         * When true, signals whose direction contradicts {@code enrichment.smcSwingBias}
         * are downgraded to a CLOSE (if open) or NONE (if flat). Null bias is a passthrough.
         * Defaults to false to preserve legacy behaviour on existing rows.
         */
        boolean swingBiasFilterEnabled,
        /**
         * User-configured number of contracts to submit for the next OPEN / REVERSE open
         * leg of this (instrument, timeframe) panel. Distinct from {@link #entryQty}, which
         * tracks the currently open position size. CLOSE / REVERSE close legs always use the
         * existing row's quantity, not this value, so changing the panel size never causes
         * a partial flatten of an open position.
         */
        int configuredOrderQty,
        /**
         * When false, the Telegram notification listener skips WTX signals for this
         * (instrument, timeframe) — used by the per-panel Telegram toggle.
         * Default is instrument-scoped: ON for MNQ / MCL (the actively traded
         * pairs), OFF elsewhere — see {@link #defaultTelegramEnabledFor(String)}.
         * The operator can flip it per panel at runtime regardless of the default.
         */
        boolean telegramNotificationsEnabled
) {
    /** Panel default — matches what the user sees on first load. */
    public static final int DEFAULT_ORDER_QTY = 2;

    /**
     * Instrument-scoped default for the Telegram notification toggle.
     * Only MNQ and MCL receive WTX Telegram alerts out of the box; other
     * instruments (MGC, 6E, …) start muted so the channel stays focused on
     * the pairs the operator actively trades. The per-panel toggle still
     * lets the operator opt in for any instrument at runtime.
     */
    public static boolean defaultTelegramEnabledFor(String instrument) {
        return "MNQ".equalsIgnoreCase(instrument) || "MCL".equalsIgnoreCase(instrument);
    }

    public static WtxStrategyState initial(String instrument, String timeframe, BigDecimal startEquity) {
        return new WtxStrategyState(
                instrument,
                timeframe,
                WtxPosition.FLAT,
                null, BigDecimal.ZERO,
                startEquity, startEquity,
                BigDecimal.ZERO,
                false,
                null,
                Instant.now(),
                WtxProfile.BASELINE,
                false,
                null, null, null,
                false,
                DEFAULT_ORDER_QTY,
                defaultTelegramEnabledFor(instrument)
        );
    }

    public WtxStrategyState withPosition(WtxPosition pos, BigDecimal entryPrice, BigDecimal qty) {
        return new WtxStrategyState(instrument, timeframe, pos, entryPrice, qty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, entryPrice, null,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withPosition(WtxPosition pos, BigDecimal entryPrice, BigDecimal qty, BigDecimal entryAtr) {
        return new WtxStrategyState(instrument, timeframe, pos, entryPrice, qty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, entryPrice, null,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withFlat(BigDecimal realizedPnlAdd) {
        BigDecimal newRealized = dailyRealizedPnl.add(realizedPnlAdd);
        BigDecimal newEquity = dayStartEquity.add(newRealized);
        return new WtxStrategyState(instrument, timeframe, WtxPosition.FLAT, null, BigDecimal.ZERO,
                dayStartEquity, newEquity, newRealized, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                null, null, null,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withDayReset(BigDecimal newStartEquity) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                newStartEquity, newStartEquity, BigDecimal.ZERO, false, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withMaxLossHit() {
        return new WtxStrategyState(instrument, timeframe, WtxPosition.FLAT, null, BigDecimal.ZERO,
                dayStartEquity, currentEquity, dailyRealizedPnl, true, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                null, null, null,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withLastCandleTs(Instant ts) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, ts, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withProfile(WtxProfile profile) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                profile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withAutoExecution(boolean enabled) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, enabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withSwingBiasFilter(boolean enabled) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                enabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withTrailing(BigDecimal bestFavorablePrice, BigDecimal trailingStopPrice) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withConfiguredOrderQty(int qty) {
        int sanitized = qty <= 0 ? DEFAULT_ORDER_QTY : qty;
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, sanitized,
                telegramNotificationsEnabled);
    }

    public WtxStrategyState withTelegramNotifications(boolean enabled) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                enabled);
    }

    /** Daily P&L = current equity - day start equity */
    public BigDecimal dailyPnl() {
        return currentEquity.subtract(dayStartEquity);
    }
}
