package com.riskdesk.application.service.strategy;

/**
 * Verdict emitted by {@link StrategyExecutionGate}. Either green-lights a trade
 * or blocks it with a structured reason that the execution service persists in
 * the audit trail.
 *
 * <p>The reason is always present (even on pass) so telemetry downstream can
 * tell why a trade was allowed — useful when we start correlating execution
 * outcomes with which playbook agreed, which decision tier, etc.
 */
public record GateOutcome(boolean allow, String reason) {

    public static GateOutcome pass(String reason) {
        return new GateOutcome(true, reason == null ? "allowed" : reason);
    }

    public static GateOutcome block(String reason) {
        return new GateOutcome(false, reason == null ? "blocked" : reason);
    }
}
