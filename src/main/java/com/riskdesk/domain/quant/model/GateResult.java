package com.riskdesk.domain.quant.model;

/**
 * Outcome of evaluating a single {@link Gate}. {@link #ok} is the boolean
 * verdict; {@link #reason} is a human-readable explanation that mirrors the
 * Python source script and powers the dashboard tooltip.
 */
public record GateResult(boolean ok, String reason) {

    public static GateResult pass(String reason) {
        return new GateResult(true, reason);
    }

    public static GateResult fail(String reason) {
        return new GateResult(false, reason);
    }
}
