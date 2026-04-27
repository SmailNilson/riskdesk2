package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.analysis.model.IndicatorSnapshot;
import com.riskdesk.domain.analysis.model.SmcContext;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;

import java.time.Instant;

/**
 * Read-only port: provide a frozen indicator + SMC view at the given decision instant.
 * <p>
 * <b>Coherence (PR #269 round-8 review):</b> indicator and SMC values must come
 * from the SAME underlying compute pass — without that guarantee, two parallel
 * cache misses could observe different candle states and the resulting
 * {@code LiveVerdict} would mix indicator readings from one generation with
 * SMC structure from another, breaking snapshot coherence and replay
 * determinism. The {@link #combinedAsOf(Instrument, Timeframe, Instant)}
 * method exists for this reason; new code should prefer it over the split
 * accessors below.
 * <p>
 * Adapters MUST tag every returned object with the {@code asOf} of the
 * underlying data so the aggregator can enforce its staleness budget.
 */
public interface IndicatorSnapshotPort {

    record TimedIndicators(IndicatorSnapshot indicators, Instant asOf) {}
    record TimedSmc(SmcContext smc, Instant asOf) {}

    /**
     * Combined view derived from a single computation. The two domain models
     * share the same {@code asOf}, guaranteeing a consistent observation of
     * the underlying candles for a given capture.
     */
    record TimedCombined(IndicatorSnapshot indicators, SmcContext smc, Instant asOf) {}

    /** Preferred entry point — preserves coherence across indicator + SMC. */
    TimedCombined combinedAsOf(Instrument instrument, Timeframe timeframe, Instant decisionAt);

    /**
     * @deprecated Use {@link #combinedAsOf} instead. Calling indicators and
     *             SMC separately can race on cache misses (PR #269 round-8).
     */
    @Deprecated
    TimedIndicators indicatorsAsOf(Instrument instrument, Timeframe timeframe, Instant decisionAt);

    /**
     * @deprecated Use {@link #combinedAsOf} instead — see {@link #indicatorsAsOf}.
     */
    @Deprecated
    TimedSmc smcAsOf(Instrument instrument, Timeframe timeframe, Instant decisionAt);
}
