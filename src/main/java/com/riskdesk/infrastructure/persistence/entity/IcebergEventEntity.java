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
 * Persists iceberg detection events from the order flow subsystem (UC-OF-014).
 * An iceberg is identified by repeated wall APPEARED/DISAPPEARED cycles at the
 * same price level within a short time window.
 * 90-day retention, purged by OrderFlowEventPersistenceService.
 */
@Entity
@Table(
    name = "order_flow_iceberg_events",
    indexes = {
        @Index(name = "idx_of_iceberg_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class IcebergEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant timestamp;

    /** BID_ICEBERG or ASK_ICEBERG. */
    @Column(nullable = false, length = 15)
    private String side;

    @Column(nullable = false)
    private double priceLevel;

    @Column(nullable = false)
    private int rechargeCount;

    @Column(nullable = false)
    private long avgRechargeSize;

    @Column(nullable = false)
    private double durationSeconds;

    @Column(nullable = false)
    private double icebergScore;

    protected IcebergEventEntity() {}

    public IcebergEventEntity(Instrument instrument, Instant timestamp, String side,
                              double priceLevel, int rechargeCount, long avgRechargeSize,
                              double durationSeconds, double icebergScore) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.side = side;
        this.priceLevel = priceLevel;
        this.rechargeCount = rechargeCount;
        this.avgRechargeSize = avgRechargeSize;
        this.durationSeconds = durationSeconds;
        this.icebergScore = icebergScore;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public String getSide() { return side; }
    public double getPriceLevel() { return priceLevel; }
    public int getRechargeCount() { return rechargeCount; }
    public long getAvgRechargeSize() { return avgRechargeSize; }
    public double getDurationSeconds() { return durationSeconds; }
    public double getIcebergScore() { return icebergScore; }
}
