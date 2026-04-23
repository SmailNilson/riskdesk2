package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.engine.strategy.model.StrategyDecision;

import java.util.Optional;

/**
 * Verdict emitted by {@link StrategyExecutionGate}. Either green-lights a trade
 * or blocks it with a structured reason that the execution service persists in
 * the audit trail.
 *
 * <p>The reason is always present (even on pass) so telemetry downstream can
 * tell why a trade was allowed — useful when we start correlating execution
 * outcomes with which playbook agreed, which decision tier, etc.
 *
 * <p>{@link #decision} carries the raw {@link StrategyDecision} on every path
 * where the engine actually evaluated — callers assembling a
 * {@code TradeBlockedByStrategyGateEvent} use it to fill the playbook id /
 * decision / score fields without parsing the reason string. Empty on pass
 * paths that short-circuit before evaluation (gate disabled globally,
 * instrument not enrolled) and on error paths where the engine couldn't run
 * (bean unavailable, throws).
 */
public record GateOutcome(
    boolean allow,
    String reason,
    Optional<StrategyDecision> decision
) {

    public GateOutcome {
        if (decision == null) decision = Optional.empty();
    }

    public static GateOutcome pass(String reason) {
        return new GateOutcome(true, reason == null ? "allowed" : reason, Optional.empty());
    }

    public static GateOutcome pass(String reason, StrategyDecision decision) {
        return new GateOutcome(true, reason == null ? "allowed" : reason, Optional.ofNullable(decision));
    }

    public static GateOutcome block(String reason) {
        return new GateOutcome(false, reason == null ? "blocked" : reason, Optional.empty());
    }

    public static GateOutcome block(String reason, StrategyDecision decision) {
        return new GateOutcome(false, reason == null ? "blocked" : reason, Optional.ofNullable(decision));
    }
}
