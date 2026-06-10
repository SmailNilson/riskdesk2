package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when a clock-aligned footprint bar closes (UC-OF-011) — either rolled
 * over by the first tick of the next bar window, or swept by the idle-close
 * scheduler when a window elapses without new ticks.
 */
public record FootprintBarClosed(
    FootprintBar bar,
    Instant timestamp
) implements DomainEvent {}
