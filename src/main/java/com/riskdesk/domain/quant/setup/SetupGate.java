package com.riskdesk.domain.quant.setup;

/**
 * A single named gate in the scalp/day-trading setup chain.
 * Implementations live in the application layer so they can access
 * application-level context without polluting the domain.
 */
@FunctionalInterface
public interface SetupGate {
    GateCheckResult check(SetupEvaluationContext ctx);
}
