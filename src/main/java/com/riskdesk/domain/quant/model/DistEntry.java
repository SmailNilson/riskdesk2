package com.riskdesk.domain.quant.model;

import java.time.Instant;

/**
 * One entry in the rolling distribution / accumulation history kept by
 * {@link QuantState}. {@code type} is "DIST" or "ACCU" — the lists are
 * intentionally separated (Option B fix) so that an isolated ACCU scan can
 * not invalidate a DIST persistence streak.
 */
public record DistEntry(String type, double conf, Instant ts) {

    public static final String DIST = "DIST";
    public static final String ACCU = "ACCU";
}
