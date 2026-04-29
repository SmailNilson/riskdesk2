package com.riskdesk.domain.quant.model;

import java.time.Instant;

/**
 * Cumulative delta + buy ratio aggregate emitted by the order-flow tick stream.
 * {@code delta} is the net signed flow over the rolling window; {@code buyRatioPct}
 * is in [0, 100]. {@link #source} is forwarded for diagnostics (e.g. real ticks
 * vs CLV estimation).
 */
public record DeltaSnapshot(
    double delta,
    Double buyRatioPct,
    Instant windowEnd,
    String source
) {
}
