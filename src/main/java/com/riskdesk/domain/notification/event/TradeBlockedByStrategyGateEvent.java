package com.riskdesk.domain.notification.event;

import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when {@code ExecutionManagerService} refuses to create a trade
 * execution because the S4 {@code StrategyExecutionGate} returned
 * {@code block} for an enrolled instrument.
 *
 * <p>This event is complementary to {@link TradeValidatedEvent}: it surfaces
 * the cases where the legacy Mentor review said ELIGIBLE but the strategy
 * engine intervened. Critical for the VETO_ONLY rollout — operators need a
 * clear signal every time the new engine actively prevented an execution
 * that would otherwise have fired.
 *
 * <p>{@link #gateReason} is the structured reason string produced by
 * {@code GateOutcome} (e.g. {@code "engine-decision=NO_TRADE score=22.0"},
 * {@code "direction-mismatch engine=SHORT review=LONG"}).
 *
 * <p>The {@code strategy*} fields mirror the ones on
 * {@link TradeValidatedEvent}; they may be null when the gate blocked for a
 * reason that doesn't involve an engine evaluation (e.g. engine unavailable).
 */
public record TradeBlockedByStrategyGateEvent(
        String instrument,
        String action,
        String timeframe,
        Long reviewId,
        String gateReason,
        String strategyPlaybookId,
        String strategyDecision,
        Double strategyFinalScore,
        Instant timestamp
) implements DomainEvent {
}
