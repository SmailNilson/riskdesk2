package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupSignal;
import com.riskdesk.domain.orderflow.perfectsetup.PerfectSetupState;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when a Perfect Setup confluence signal changes state — armed,
 * triggered, invalidated or expired. Transition-based: emitted only on a state
 * change, never on every scan while the state persists.
 *
 * @param instrument    the futures instrument
 * @param signal        the new signal (direction, axes checklist, trade plan)
 * @param previousState the state before this transition
 * @param timestamp     event time (UTC)
 */
public record PerfectSetupDetected(
    Instrument instrument,
    PerfectSetupSignal signal,
    PerfectSetupState previousState,
    Instant timestamp
) implements DomainEvent {}
