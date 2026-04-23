package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when aggressive momentum (trend continuation / capitulation) is
 * detected — delta, price movement, and volume all aligned in the same direction.
 */
public record MomentumBurstDetected(
    Instrument instrument,
    MomentumSignal signal,
    Instant timestamp
) implements DomainEvent {}
