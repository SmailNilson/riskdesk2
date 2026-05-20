package com.riskdesk.domain.quant.setup;

import java.util.List;

/**
 * Ordered chain of {@link SetupGate}s that all must pass for a setup to
 * qualify. Pure domain logic — no Spring, no I/O.
 */
public class SetupGateChain {

    private final List<SetupGate> gates;

    public SetupGateChain(List<SetupGate> gates) {
        this.gates = List.copyOf(gates);
    }

    /** Evaluates every gate and returns all results (pass + fail). */
    public List<GateCheckResult> evaluateAll(SetupEvaluationContext ctx) {
        return gates.stream()
            .map(g -> g.check(ctx))
            .toList();
    }

    /** Returns true only when every gate in {@code results} has passed. */
    public static boolean allPassed(List<GateCheckResult> results) {
        return results.stream().allMatch(GateCheckResult::passed);
    }
}
