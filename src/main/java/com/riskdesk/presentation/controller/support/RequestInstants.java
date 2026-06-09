package com.riskdesk.presentation.controller.support;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Parses time-range query parameters shared by the candle range read and backfill endpoints.
 * Accepts either an ISO-8601 instant (e.g. {@code 2026-03-01T00:00:00Z}) or a bare epoch-seconds value.
 */
public final class RequestInstants {

    private RequestInstants() {}

    /**
     * @throws IllegalArgumentException if {@code raw} is blank or neither ISO-8601 nor epoch seconds
     */
    public static Instant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("blank instant");
        }
        String trimmed = raw.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(trimmed));
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Unparseable instant: " + raw);
            }
        }
    }
}
