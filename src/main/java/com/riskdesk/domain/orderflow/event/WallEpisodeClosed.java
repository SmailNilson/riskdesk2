package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.WallEpisode;

import java.time.Instant;

/**
 * Published when a tracked order-book wall episode is finalized with an outcome
 * (CONSUMED / PULLED / FADED / OUT_OF_RANGE). Consumed by the persistence layer
 * for the wall traceability history.
 */
public record WallEpisodeClosed(
    Instrument instrument,
    WallEpisode episode,
    Instant timestamp
) {}
