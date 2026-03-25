package com.riskdesk.domain.alert.model;

import java.time.Instant;

public record Alert(
    String key,
    AlertSeverity severity,
    String message,
    AlertCategory category,
    String instrument,
    Instant timestamp
) {
    public Alert(String key, AlertSeverity severity, String message, AlertCategory category, String instrument) {
        this(key, severity, message, category, instrument, Instant.now());
    }
}
