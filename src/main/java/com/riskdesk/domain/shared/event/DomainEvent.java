package com.riskdesk.domain.shared.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker interface for domain events.
 * All domain events are implemented as records with an Instant timestamp field.
 */
public interface DomainEvent {

    Instant timestamp();

    /**
     * Generates a unique event ID. Can be used by implementations if needed.
     */
    static String newEventId() {
        return UUID.randomUUID().toString();
    }
}
