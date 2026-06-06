package com.riskdesk.presentation.quant.dto;

/**
 * Wire representation of a single gate verdict. {@code abstain} marks a gate that could not be
 * evaluated because its input was unavailable (e.g. the delta feed is down) rather than a
 * directional miss — so the UI can distinguish "feed down" from a genuine fail.
 */
public record QuantGateView(String gate, boolean ok, boolean abstain, String reason) {
}
