package com.riskdesk.domain.calendar;

import java.time.Instant;
import java.util.Objects;

/**
 * A scheduled macro / news event that warrants a trading blackout window
 * (e.g. FOMC rate decision, NFP release, CPI print, ECB press conference).
 *
 * <p>The event carries its scheduled UTC {@code timestamp}, a human-readable
 * {@code name} and an {@link Impact} classification so operators can see why
 * a blackout is active and so future work can gate blackouts by severity.
 *
 * <p>This is a pure domain record — no Spring, no JPA.
 */
public record NewsEvent(Instant timestamp, String name, Impact impact) {

    public enum Impact {
        LOW,
        MEDIUM,
        HIGH
    }

    public NewsEvent {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(impact, "impact must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
