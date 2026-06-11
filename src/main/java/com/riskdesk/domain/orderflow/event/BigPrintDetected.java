package com.riskdesk.domain.orderflow.event;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.BigPrint;
import com.riskdesk.domain.shared.event.DomainEvent;

import java.time.Instant;

/**
 * Published when an outsized trade print is flagged (UC-OF-BIGPRINT).
 * Rate-limited at the emitting adapter to max 1/sec per instrument.
 * Not persisted — fan-out is WebSocket-only ({@code /topic/big-prints}).
 */
public record BigPrintDetected(
    Instrument instrument,
    BigPrint print,
    Instant timestamp
) implements DomainEvent {}
