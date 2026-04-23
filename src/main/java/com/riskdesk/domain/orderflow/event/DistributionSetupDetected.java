package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.DistributionSignal;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when institutional distribution or accumulation is detected —
 * a sustained sequence of same-side absorption events signalling smart money
 * positioning before a directional move.
 */
public record DistributionSetupDetected(
    Instrument instrument,
    DistributionSignal signal,
    Instant timestamp
) implements DomainEvent {}
