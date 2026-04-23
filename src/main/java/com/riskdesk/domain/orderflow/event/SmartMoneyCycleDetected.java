package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.SmartMoneyCycleSignal;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when a complete smart-money cycle is detected (distribution → momentum → accumulation, or the mirror).
 * <p>
 * Partial cycles (phase 1+2 only) may also be published with lower confidence;
 * listeners can filter on {@code signal.currentPhase() == COMPLETE} to act only
 * on fully-chained setups.
 */
public record SmartMoneyCycleDetected(
    Instrument instrument,
    SmartMoneyCycleSignal signal,
    Instant timestamp
) implements DomainEvent {}
