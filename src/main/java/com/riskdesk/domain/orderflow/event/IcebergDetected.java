package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.IcebergSignal;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when an iceberg order pattern is detected at a price level (UC-OF-014).
 * An iceberg is identified by repeated wall APPEARED/DISAPPEARED cycles at the
 * same price level within a short time window.
 */
public record IcebergDetected(
    Instrument instrument,
    IcebergSignal signal,
    Instant timestamp
) implements DomainEvent {}
