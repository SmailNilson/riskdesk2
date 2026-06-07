package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A WaveTrend XT signal detected on a closed candle.
 * Carries the raw WT values, the suggested action, canTrade gate result,
 * informative enrichment data, and the IBKR routing outcome (null when routing
 * was never attempted — e.g. action NONE).
 */
public record WtxSignal(
        String instrument,
        String timeframe,
        WtxSignalType signalType,
        /** LONG for COMPRA/COMPRA_1, SHORT for VENTA/VENTA_1 */
        String direction,
        BigDecimal wt1Value,
        BigDecimal wt2Value,
        /** True when signal fires AND canTrade gate passes (not in max-loss or force-close window) */
        boolean canTrade,
        WtxAction suggestedAction,
        WtxEnrichmentSnapshot enrichment,
        Instant signalTs,
        /** Outcome of IBKR auto-execution routing; null when routing was never attempted. */
        WtxRoutingOutcome routingOutcome,
        /**
         * Human-readable error message surfaced to the UI (tooltip) when the routing
         * outcome is a failure or insufficient-margin skip. Null otherwise. Truncated
         * upstream to fit the {@code wtx_signal_history.routingErrorMessage} column
         * (300 chars).
         */
        String routingErrorMessage,
        /**
         * Candle-close price at signal detection — the entry/reference price surfaced
         * in the UI. Null for signals persisted before this field existed.
         */
        BigDecimal price,
        /**
         * Why an open position was closed (TRAILING_TP / STOP_LOSS / REVERSE / FORCE_CLOSE /
         * MAX_LOSS / SWING_BIAS). Null on OPEN / NONE signals and on rows persisted before
         * this field existed.
         */
        WtxExitType exitType,
        /**
         * Realized P&L (USD) booked when this signal CLOSED a position — the delta added to the
         * day's realized P&L for this round-trip. Null on OPEN / NONE signals and on rows persisted
         * before this field existed. The sum across a CME trading day reconciles with the panel's
         * Daily P&L (both are {@code dailyRealizedPnl}-based).
         */
        BigDecimal realizedPnl
) {
    public WtxSignal withEnrichment(WtxEnrichmentSnapshot enrichment) {
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, suggestedAction, enrichment, signalTs,
                routingOutcome, routingErrorMessage, price, exitType, realizedPnl);
    }

    public WtxSignal withAction(WtxAction action) {
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, action, enrichment, signalTs,
                routingOutcome, routingErrorMessage, price, exitType, realizedPnl);
    }

    public WtxSignal withRoutingOutcome(WtxRoutingOutcome routingOutcome) {
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, suggestedAction, enrichment, signalTs,
                routingOutcome, routingErrorMessage, price, exitType, realizedPnl);
    }

    /** Stamps the candle-close price at signal detection (the UI's ENTRY price). */
    public WtxSignal withPrice(BigDecimal price) {
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, suggestedAction, enrichment, signalTs,
                routingOutcome, routingErrorMessage, price, exitType, realizedPnl);
    }

    /** Tags the close reason (TP / SL / REVERSE / …) so the UI can distinguish exit kinds. */
    public WtxSignal withExitType(WtxExitType exitType) {
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, suggestedAction, enrichment, signalTs,
                routingOutcome, routingErrorMessage, price, exitType, realizedPnl);
    }

    /**
     * Combined routing setter — applies outcome AND error message in a single call.
     * Preferred over chaining {@link #withRoutingOutcome(WtxRoutingOutcome)} when the
     * caller has a {@link WtxRoutingResult} in hand.
     */
    public WtxSignal withRouting(WtxRoutingResult result) {
        if (result == null) {
            return this;
        }
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, suggestedAction, enrichment, signalTs,
                result.outcome(), result.errorMessage(), price, exitType, realizedPnl);
    }

    /**
     * Stamps the realized P&L (USD) booked by this close. Null/absent on opens.
     * Mirrors {@link #withPrice(BigDecimal)} / {@link #withExitType(WtxExitType)} — used by the
     * strategy service after a close to surface per-trade P&L in the history.
     */
    public WtxSignal withRealizedPnl(BigDecimal realizedPnl) {
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, suggestedAction, enrichment, signalTs,
                routingOutcome, routingErrorMessage, price, exitType, realizedPnl);
    }
}
