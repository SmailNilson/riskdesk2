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
        String routingErrorMessage
) {
    public WtxSignal withEnrichment(WtxEnrichmentSnapshot enrichment) {
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, suggestedAction, enrichment, signalTs,
                routingOutcome, routingErrorMessage);
    }

    public WtxSignal withRoutingOutcome(WtxRoutingOutcome routingOutcome) {
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, suggestedAction, enrichment, signalTs,
                routingOutcome, routingErrorMessage);
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
                result.outcome(), result.errorMessage());
    }
}
