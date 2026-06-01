package com.riskdesk.domain.engine.strategy.wtxrsi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-(instrument, timeframe) runtime state of the WTX+RSI strategy.
 *
 * <p>Carries the live position, the gating toggles, and the user-configured
 * order quantity. The shape is intentionally a slimmer mirror of
 * {@code WtxStrategyState} so the persistence and UI logic can stay parallel.
 *
 * <p><b>Why per-timeframe?</b> A 5m position must never block a 10m signal
 * (different style, different bar size, different risk envelope), so each
 * (instrument, timeframe) row carries its own state.
 */
public record WtxRsiStrategyState(
        String instrument,
        String timeframe,
        WtxRsiPosition currentPosition,
        /** Entry price of the open position, null when FLAT. */
        BigDecimal entryPrice,
        /** Open quantity (contracts), 0 when FLAT. */
        BigDecimal entryQty,
        /** Active stop-loss on the open position, null when FLAT. */
        BigDecimal stopLoss,
        /** Active take-profit on the open position, null when FLAT or REVERSAL mode. */
        BigDecimal takeProfit,
        /**
         * Realized P&L for the <b>current CME trading day</b> (17:00 ET → 17:00 ET).
         * The reducer zeroes this at each session boundary via {@link #withDailyPnlReset()},
         * so it is a per-day figure, not an all-time running total. Informational;
         * never blocks.
         */
        BigDecimal cumulativeRealizedPnl,
        /** Timestamp of the most recently processed candle. */
        Instant lastCandleTs,
        Instant updatedAt,
        /** True when user has flipped the IBKR routing switch ON for this (instrument, timeframe). */
        boolean autoExecutionEnabled,
        /** User-configured contracts for the next OPEN; size-on-confirmation handled by RiskCalculator. */
        int configuredOrderQty,
        /**
         * When true, signals whose direction contradicts the resolved
         * {@link WtxRsiSwingBias} are suppressed; an open position pointing
         * against the bias is downgraded to a CLOSE. NEUTRAL bias passes through.
         */
        boolean swingBiasFilterEnabled,
        /** Snapshot of the most recently resolved bias (UI display + reasoning hint). */
        WtxRsiSwingBias lastSwingBias,
        /**
         * Entry-only Chaikin gate for this (instrument, timeframe). When true, an
         * OPEN is allowed only if the signal is Chaikin-confirmed; unconfirmed
         * signals are suppressed. Exits (reversal / SL / TP) are unaffected.
         * Per-panel runtime toggle; the initial value is inherited from the global
         * {@code riskdesk.wtxrsi.chaikin-required} config default.
         */
        boolean chaikinRequired
) {

    public static final int DEFAULT_ORDER_QTY = 1;

    /** Fresh state with the Chaikin gate ON (matches the shipped global default). */
    public static WtxRsiStrategyState initial(String instrument, String timeframe) {
        return initial(instrument, timeframe, true);
    }

    /** Fresh state seeding the Chaikin entry gate from the caller (global config default). */
    public static WtxRsiStrategyState initial(String instrument, String timeframe, boolean chaikinRequired) {
        return new WtxRsiStrategyState(
                instrument, timeframe, WtxRsiPosition.FLAT,
                null, BigDecimal.ZERO, null, null,
                BigDecimal.ZERO,
                null, Instant.now(),
                false,
                DEFAULT_ORDER_QTY,
                false,
                WtxRsiSwingBias.NEUTRAL,
                chaikinRequired
        );
    }

    public WtxRsiStrategyState withPosition(
            WtxRsiPosition pos, BigDecimal entryPrice, BigDecimal qty,
            BigDecimal stopLoss, BigDecimal takeProfit) {
        return new WtxRsiStrategyState(
                instrument, timeframe, pos, entryPrice, qty, stopLoss, takeProfit,
                cumulativeRealizedPnl, lastCandleTs, Instant.now(),
                autoExecutionEnabled, configuredOrderQty,
                swingBiasFilterEnabled, lastSwingBias, chaikinRequired
        );
    }

    public WtxRsiStrategyState withFlat(BigDecimal realizedPnlAdd) {
        BigDecimal updated = cumulativeRealizedPnl.add(realizedPnlAdd);
        return new WtxRsiStrategyState(
                instrument, timeframe, WtxRsiPosition.FLAT,
                null, BigDecimal.ZERO, null, null,
                updated, lastCandleTs, Instant.now(),
                autoExecutionEnabled, configuredOrderQty,
                swingBiasFilterEnabled, lastSwingBias, chaikinRequired
        );
    }

    /**
     * Zero the realized-P&L accumulator. Called by the reducer at the CME
     * trading-day boundary so {@link #cumulativeRealizedPnl} reflects only the
     * current session's realized P&L rather than an all-time running total.
     */
    public WtxRsiStrategyState withDailyPnlReset() {
        return new WtxRsiStrategyState(
                instrument, timeframe, currentPosition, entryPrice, entryQty, stopLoss, takeProfit,
                BigDecimal.ZERO, lastCandleTs, Instant.now(),
                autoExecutionEnabled, configuredOrderQty,
                swingBiasFilterEnabled, lastSwingBias, chaikinRequired
        );
    }

    public WtxRsiStrategyState withLastCandleTs(Instant ts) {
        return new WtxRsiStrategyState(
                instrument, timeframe, currentPosition, entryPrice, entryQty, stopLoss, takeProfit,
                cumulativeRealizedPnl, ts, Instant.now(),
                autoExecutionEnabled, configuredOrderQty,
                swingBiasFilterEnabled, lastSwingBias, chaikinRequired
        );
    }

    public WtxRsiStrategyState withAutoExecution(boolean enabled) {
        return new WtxRsiStrategyState(
                instrument, timeframe, currentPosition, entryPrice, entryQty, stopLoss, takeProfit,
                cumulativeRealizedPnl, lastCandleTs, Instant.now(),
                enabled, configuredOrderQty,
                swingBiasFilterEnabled, lastSwingBias, chaikinRequired
        );
    }

    public WtxRsiStrategyState withConfiguredOrderQty(int qty) {
        int sanitized = qty <= 0 ? DEFAULT_ORDER_QTY : qty;
        return new WtxRsiStrategyState(
                instrument, timeframe, currentPosition, entryPrice, entryQty, stopLoss, takeProfit,
                cumulativeRealizedPnl, lastCandleTs, Instant.now(),
                autoExecutionEnabled, sanitized,
                swingBiasFilterEnabled, lastSwingBias, chaikinRequired
        );
    }

    public WtxRsiStrategyState withSwingBiasFilter(boolean enabled) {
        return new WtxRsiStrategyState(
                instrument, timeframe, currentPosition, entryPrice, entryQty, stopLoss, takeProfit,
                cumulativeRealizedPnl, lastCandleTs, Instant.now(),
                autoExecutionEnabled, configuredOrderQty,
                enabled, lastSwingBias, chaikinRequired
        );
    }

    public WtxRsiStrategyState withLastSwingBias(WtxRsiSwingBias bias) {
        return new WtxRsiStrategyState(
                instrument, timeframe, currentPosition, entryPrice, entryQty, stopLoss, takeProfit,
                cumulativeRealizedPnl, lastCandleTs, Instant.now(),
                autoExecutionEnabled, configuredOrderQty,
                swingBiasFilterEnabled, bias, chaikinRequired
        );
    }

    public WtxRsiStrategyState withChaikinRequired(boolean required) {
        return new WtxRsiStrategyState(
                instrument, timeframe, currentPosition, entryPrice, entryQty, stopLoss, takeProfit,
                cumulativeRealizedPnl, lastCandleTs, Instant.now(),
                autoExecutionEnabled, configuredOrderQty,
                swingBiasFilterEnabled, lastSwingBias, required
        );
    }
}
