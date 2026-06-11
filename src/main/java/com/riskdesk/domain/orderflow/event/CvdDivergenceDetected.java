package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.CvdDivergenceSignal;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when a confirmed price/CVD pivot divergence is detected (UC-OF-CVD).
 * Not persisted — fan-out is WebSocket-only ({@code /topic/cvd-divergence}).
 */
public record CvdDivergenceDetected(
    Instrument instrument,
    CvdDivergenceSignal signal,
    Instant timestamp
) implements DomainEvent {}
