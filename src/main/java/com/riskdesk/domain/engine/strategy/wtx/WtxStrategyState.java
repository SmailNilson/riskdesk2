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
        boolean telegramNotificationsEnabled,
        /**
         * Close P&L booked OPTIMISTICALLY on a close-submission whose broker fill is not yet confirmed
         * (auto-execution only). It is added to {@link #dailyRealizedPnl} immediately so the panel/cap
         * react, but tracked here so it can be ROLLED BACK if the close ends up not completing (the close
         * order rests then cancels/expires). The close settler clears it once the close is confirmed (the
         * position is actually flat) or rolls it back when the position is still live. {@code 0} = nothing
         * pending. Always non-null.
         */
        BigDecimal pendingClosePnl
) {
    /** Panel default — matches what the user sees on first load. */
    public static final int DEFAULT_ORDER_QTY = 2;

    /** Compact canonical constructor — defensively normalise {@code pendingClosePnl} to non-null. */
    public WtxStrategyState {
        if (pendingClosePnl == null) {
            pendingClosePnl = BigDecimal.ZERO;
        }
    }

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
                defaultTelegramEnabledFor(instrument),
                BigDecimal.ZERO
        );
    }

    public WtxStrategyState withPosition(WtxPosition pos, BigDecimal entryPrice, BigDecimal qty) {
        return new WtxStrategyState(instrument, timeframe, pos, entryPrice, qty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, entryPrice, null,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, BigDecimal.ZERO);
    }

    public WtxStrategyState withPosition(WtxPosition pos, BigDecimal entryPrice, BigDecimal qty, BigDecimal entryAtr) {
        return new WtxStrategyState(instrument, timeframe, pos, entryPrice, qty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, entryPrice, null,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, BigDecimal.ZERO);
    }

    /**
     * Correct only the open position's entry price + quantity to the broker's actual fill, preserving the
     * side, trailing state (best favorable price / trailing stop), ATR and pending-close marker. Used by the
     * position reconciler when the execution row is on the SAME side but carries a different (real) fill than
     * the optimistic entry {@code applyAction} recorded — so P&L and trailing track execution truth without
     * restarting the trailing buffer.
     */
    public WtxStrategyState withEntryDetails(BigDecimal newEntryPrice, BigDecimal newEntryQty) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, newEntryPrice, newEntryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, pendingClosePnl);
    }

    public WtxStrategyState withFlat(BigDecimal realizedPnlAdd) {
        BigDecimal newRealized = dailyRealizedPnl.add(realizedPnlAdd);
        BigDecimal newEquity = dayStartEquity.add(newRealized);
        return new WtxStrategyState(instrument, timeframe, WtxPosition.FLAT, null, BigDecimal.ZERO,
                dayStartEquity, newEquity, newRealized, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                null, null, null,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, pendingClosePnl);
    }

    public WtxStrategyState withDayReset(BigDecimal newStartEquity) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                newStartEquity, newStartEquity, BigDecimal.ZERO, false, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, BigDecimal.ZERO);
    }

    public WtxStrategyState withMaxLossHit() {
        return new WtxStrategyState(instrument, timeframe, WtxPosition.FLAT, null, BigDecimal.ZERO,
                dayStartEquity, currentEquity, dailyRealizedPnl, true, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                null, null, null,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, BigDecimal.ZERO);
    }

    public WtxStrategyState withLastCandleTs(Instant ts) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, ts, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, pendingClosePnl);
    }

    public WtxStrategyState withProfile(WtxProfile profile) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                profile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, pendingClosePnl);
    }

    public WtxStrategyState withAutoExecution(boolean enabled) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, enabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, pendingClosePnl);
    }

    public WtxStrategyState withSwingBiasFilter(boolean enabled) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                enabled, configuredOrderQty,
                telegramNotificationsEnabled, pendingClosePnl);
    }

    public WtxStrategyState withTrailing(BigDecimal bestFavorablePrice, BigDecimal trailingStopPrice) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, pendingClosePnl);
    }

    public WtxStrategyState withConfiguredOrderQty(int qty) {
        int sanitized = qty <= 0 ? DEFAULT_ORDER_QTY : qty;
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, sanitized,
                telegramNotificationsEnabled, pendingClosePnl);
    }

    public WtxStrategyState withTelegramNotifications(boolean enabled) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                enabled, pendingClosePnl);
    }

    /**
     * Record that {@code pnl} was just booked by an optimistic close whose broker fill is unconfirmed, so
     * the close settler can roll it back if the close does not complete. Preserves every other field.
     */
    public WtxStrategyState withPendingClose(BigDecimal pnl) {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, pnl == null ? BigDecimal.ZERO : pnl);
    }

    /**
     * The optimistic close did NOT complete (the close order rested then cancelled/expired) — un-book the
     * pending close P&L from realized + equity and clear the marker. The position side is corrected
     * separately (by the position reconciler) from execution-row truth.
     */
    public WtxStrategyState withClosePnlRolledBack() {
        BigDecimal newRealized = dailyRealizedPnl.subtract(pendingClosePnl);
        BigDecimal newEquity = dayStartEquity.add(newRealized);
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, newEquity, newRealized, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, BigDecimal.ZERO);
    }

    /** The optimistic close is now broker-confirmed — the booked P&L is final; just clear the marker. */
    public WtxStrategyState withPendingClosePnlFinalized() {
        return new WtxStrategyState(instrument, timeframe, currentPosition, entryPrice, entryQty,
                dayStartEquity, currentEquity, dailyRealizedPnl, maxLossHit, lastCandleTs, Instant.now(),
                activeProfile, autoExecutionEnabled,
                entryAtr, bestFavorablePrice, trailingStopPrice,
                swingBiasFilterEnabled, configuredOrderQty,
                telegramNotificationsEnabled, BigDecimal.ZERO);
    }

    /** True when an optimistic close is awaiting broker confirmation (non-zero pending P&L marker). */
    public boolean hasPendingClose() {
        return pendingClosePnl != null && pendingClosePnl.signum() != 0;
    }

    /** Daily P&L = current equity - day start equity */
    public BigDecimal dailyPnl() {
        return currentEquity.subtract(dayStartEquity);
    }
}
