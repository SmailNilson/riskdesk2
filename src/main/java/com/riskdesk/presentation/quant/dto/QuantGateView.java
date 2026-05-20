package com.riskdesk.presentation.quant.dto;

/**
 * Wire representation of a single gate verdict.
 */
public record QuantGateView(String gate, boolean ok, String reason) {
}
