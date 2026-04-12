package com.riskdesk.infrastructure.persistence.entity;

import com.riskdesk.domain.model.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persists spoofing detection events from the order flow subsystem (UC-OF-005).
 * A large order (wall) appeared in the book and disappeared before execution.
 * 90-day retention, purged by OrderFlowEventPersistenceService.
 */
@Entity
@Table(
    name = "order_flow_spoofing_events",
    indexes = {
        @Index(name = "idx_of_spoofing_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class SpoofingEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant timestamp;

    /** BID_SPOOF or ASK_SPOOF. */
    @Column(nullable = false, length = 15)
    private String side;

    @Column(nullable = false)
    private double priceLevel;

    @Column(nullable = false)
    private long wallSize;

    @Column(nullable = false)
    private double durationSeconds;

    @Column(nullable = false)
    private boolean priceCrossed;

    @Column(nullable = false)
    private double spoofScore;

    protected SpoofingEventEntity() {}

    public SpoofingEventEntity(Instrument instrument, Instant timestamp, String side,
                               double priceLevel, long wallSize, double durationSeconds,
                               boolean priceCrossed, double spoofScore) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.side = side;
        this.priceLevel = priceLevel;
        this.wallSize = wallSize;
        this.durationSeconds = durationSeconds;
        this.priceCrossed = priceCrossed;
        this.spoofScore = spoofScore;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public String getSide() { return side; }
    public double getPriceLevel() { return priceLevel; }
    public long getWallSize() { return wallSize; }
    public double getDurationSeconds() { return durationSeconds; }
    public boolean isPriceCrossed() { return priceCrossed; }
    public double getSpoofScore() { return spoofScore; }
}
