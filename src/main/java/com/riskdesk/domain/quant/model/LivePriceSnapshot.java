package com.riskdesk.domain.quant.model;

import java.time.Instant;

/**
 * Live-price view used by the Quant evaluator. {@link #source} holds the
 * provenance string ({@code LIVE_PUSH}, {@code DB_FALLBACK}, ...) and is the
 * input to {@link Gate#G6_LIVE_PUSH G6}.
 */
public record LivePriceSnapshot(double price, Instant timestamp, String source) {
}
