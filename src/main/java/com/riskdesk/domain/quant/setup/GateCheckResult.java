package com.riskdesk.domain.quant.setup;

/**
 * Outcome of a single {@link SetupGate} evaluation.
 *
 * @param gateName  human-readable identifier of the gate
 * @param passed    true when the gate is satisfied
 * @param reason    short explanation (useful for debugging / UI)
 */
public record GateCheckResult(String gateName, boolean passed, String reason) {

    public static GateCheckResult pass(String gateName, String reason) {
        return new GateCheckResult(gateName, true, reason);
    }

    public static GateCheckResult fail(String gateName, String reason) {
        return new GateCheckResult(gateName, false, reason);
    }
}
