package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when institutional absorption is detected at a price level (UC-OF-004).
 */
public record AbsorptionDetected(
    Instrument instrument,
    AbsorptionSignal signal,
    Instant timestamp
) implements DomainEvent {}
