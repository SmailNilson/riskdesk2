package com.riskdesk.domain.quant.model;

/**
 * Outcome of evaluating a single {@link Gate}. {@link #ok} is the boolean
 * verdict; {@link #reason} is a human-readable explanation that mirrors the
 * Python source script and powers the dashboard tooltip.
 *
 * <p>{@link #abstain} marks a gate that could not be evaluated because its input was
 * <i>unavailable</i> (e.g. the delta feed is down) rather than directionally failing. An
 * abstaining gate is still {@code ok=false} (it does not pass / count toward the score), but the
 * flag lets the dashboard, narration and advisor distinguish "feed down → cannot confirm" from a
 * genuine directional miss, so a low score during a tick-feed outage is not read as a real signal.</p>
 */
public record GateResult(boolean ok, String reason, boolean abstain) {

    /** Backward-compatible 2-arg form — a normal (non-abstaining) verdict. */
    public GateResult(boolean ok, String reason) {
        this(ok, reason, false);
    }

    public static GateResult pass(String reason) {
        return new GateResult(true, reason, false);
    }

    public static GateResult fail(String reason) {
        return new GateResult(false, reason, false);
    }

    /** Gate could not be evaluated because its input was unavailable (feed down). */
    public static GateResult abstain(String reason) {
        return new GateResult(false, reason, true);
    }
}
