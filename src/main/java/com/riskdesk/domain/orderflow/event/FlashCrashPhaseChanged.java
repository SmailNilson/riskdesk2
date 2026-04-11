package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.CrashPhase;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when the Flash Crash FSM transitions between phases (UC-OF-006).
 */
public record FlashCrashPhaseChanged(
    Instrument instrument,
    CrashPhase previousPhase,
    CrashPhase currentPhase,
    int conditionsMet,
    boolean[] conditions,
    double reversalScore,
    Instant timestamp
) implements DomainEvent {}
