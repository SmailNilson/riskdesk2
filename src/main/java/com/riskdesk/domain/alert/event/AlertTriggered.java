package com.riskdesk.domain.alert.event;

import java.time.Instant;

/**
 * Domain event raised when an alert is triggered.
 */
public record AlertTriggered(
        String severity,
        String category,
        String message,
        String instrument,
        Instant timestamp
) {}
