package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.analysis.model.IndicatorSnapshot;
import com.riskdesk.domain.analysis.model.SmcContext;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;

import java.time.Instant;

/**
 * Read-only port: provide a frozen indicator + SMC view at the given decision instant.
 * <p>
 * The infrastructure adapter is allowed to use cached state but MUST tag every
 * returned object with the {@code asOf} of the underlying data so the
 * aggregator can reject stale snapshots.
 */
public interface IndicatorSnapshotPort {

    record TimedIndicators(IndicatorSnapshot indicators, Instant asOf) {}
    record TimedSmc(SmcContext smc, Instant asOf) {}

    TimedIndicators indicatorsAsOf(Instrument instrument, Timeframe timeframe, Instant decisionAt);

    TimedSmc smcAsOf(Instrument instrument, Timeframe timeframe, Instant decisionAt);
}
