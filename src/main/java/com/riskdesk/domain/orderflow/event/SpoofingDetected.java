package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.SpoofingSignal;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when a potential spoofing pattern is detected in the order book (UC-OF-005).
 */
public record SpoofingDetected(
    Instrument instrument,
    SpoofingSignal signal,
    Instant timestamp
) implements DomainEvent {}
