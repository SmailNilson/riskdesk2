package com.riskdesk.domain.analysis.model;

import java.time.Instant;

/**
 * Tiny projection of the persisted order-flow events used by the scoring engine.
 * Decoupled from the JPA entities so the domain stays clean.
 */
public record OrderFlowEventSummary(
    Instant timestamp,
    String type,                  // "MOMENTUM" / "ABSORPTION" / "DISTRIBUTION" / "CYCLE"
    String side,                  // BULLISH / BEARISH / DIST / ACCUM / etc. — type-specific
    double score,
    long delta                    // signed; 0 if not applicable
) {
}
