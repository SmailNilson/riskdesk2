package com.riskdesk.domain.behaviouralert.model;

import java.time.Instant;

/**
 * A signal emitted by a behaviour alert rule.
 * Severity is always WARNING — behaviour alerts indicate noteworthy market conditions.
 */
public record BehaviourAlertSignal(
    String key,
    BehaviourAlertCategory category,
    String message,
    String instrument,
    Instant timestamp
) {}
