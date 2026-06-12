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
 * Persists confirmed price/CVD pivot divergences (UC-OF-CVD) for event studies.
 * All sessions are recorded (the RTH gate applies to paper trading, not persistence).
 * 90-day retention, purged by OrderFlowEventPersistenceService.
 */
@Entity
@Table(
    name = "order_flow_cvd_divergence_events",
    indexes = {
        @Index(name = "idx_of_cvddiv_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class CvdDivergenceEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    /** Detection (confirmation) time — pivotBars minutes after the pivot printed. */
    @Column(nullable = false)
    private Instant timestamp;

    /** BULLISH_DIVERGENCE or BEARISH_DIVERGENCE. */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false)
    private double prevPivotPrice;

    @Column(nullable = false)
    private double newPivotPrice;

    @Column(nullable = false)
    private long prevPivotCvd;

    @Column(nullable = false)
    private long newPivotCvd;

    /** Open time of the 1m bar that formed the new pivot. */
    @Column(nullable = false)
    private Instant pivotTimestamp;

    /** Last traded price at detection time. */
    @Column(nullable = false)
    private double priceAtDetection;

    /** True when detection fell inside the RTH window (09:30–16:00 ET). */
    @Column(nullable = false)
    private boolean rth;

    protected CvdDivergenceEventEntity() {}

    public CvdDivergenceEventEntity(Instrument instrument, Instant timestamp, String type,
                                    double prevPivotPrice, double newPivotPrice,
                                    long prevPivotCvd, long newPivotCvd,
                                    Instant pivotTimestamp, double priceAtDetection, boolean rth) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.type = type;
        this.prevPivotPrice = prevPivotPrice;
        this.newPivotPrice = newPivotPrice;
        this.prevPivotCvd = prevPivotCvd;
        this.newPivotCvd = newPivotCvd;
        this.pivotTimestamp = pivotTimestamp;
        this.priceAtDetection = priceAtDetection;
        this.rth = rth;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public String getType() { return type; }
    public double getPrevPivotPrice() { return prevPivotPrice; }
    public double getNewPivotPrice() { return newPivotPrice; }
    public long getPrevPivotCvd() { return prevPivotCvd; }
    public long getNewPivotCvd() { return newPivotCvd; }
    public Instant getPivotTimestamp() { return pivotTimestamp; }
    public double getPriceAtDetection() { return priceAtDetection; }
    public boolean isRth() { return rth; }
}
