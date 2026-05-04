package com.riskdesk.domain.engine.strategy.wtx;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A WaveTrend XT signal detected on a closed candle.
 * Carries the raw WT values, the suggested action, canTrade gate result,
 * and informative enrichment data.
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
        Instant signalTs
) {
    public WtxSignal withEnrichment(WtxEnrichmentSnapshot enrichment) {
        return new WtxSignal(instrument, timeframe, signalType, direction,
                wt1Value, wt2Value, canTrade, suggestedAction, enrichment, signalTs);
    }
}
