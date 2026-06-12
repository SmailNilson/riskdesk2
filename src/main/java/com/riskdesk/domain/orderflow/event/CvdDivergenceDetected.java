package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.CvdDivergenceSignal;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when a confirmed price/CVD pivot divergence is detected (UC-OF-CVD).
 * Fan-out: {@code /topic/cvd-divergence} WebSocket payload, event persistence
 * ({@code order_flow_cvd_divergence_events}) and the RTH-gated paper-trading loop.
 *
 * @param lastPrice last traded price at detection time — the paper fill reference.
 *                  Distinct from {@link CvdDivergenceSignal#newPivotPrice()}: the pivot
 *                  is confirmed {@code pivotBars} minutes after it printed.
 */
public record CvdDivergenceDetected(
    Instrument instrument,
    CvdDivergenceSignal signal,
    double lastPrice,
    Instant timestamp
) implements DomainEvent {}
