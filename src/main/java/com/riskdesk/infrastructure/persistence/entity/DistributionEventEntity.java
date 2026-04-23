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
 * Persists institutional distribution / accumulation detection events.
 * 90-day retention, purged by OrderFlowEventPersistenceService.
 */
@Entity
@Table(
    name = "order_flow_distribution_events",
    indexes = {
        @Index(name = "idx_of_distribution_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class DistributionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant timestamp;

    /** DISTRIBUTION or ACCUMULATION. */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false)
    private int consecutiveCount;

    @Column(nullable = false)
    private double avgScore;

    @Column(nullable = false)
    private double totalDurationSeconds;

    @Column(nullable = false)
    private double priceAtDetection;

    @Column(nullable = true)
    private Double resistanceLevel;

    @Column(nullable = false)
    private int confidenceScore;

    protected DistributionEventEntity() {}

    public DistributionEventEntity(Instrument instrument, Instant timestamp, String type,
                                    int consecutiveCount, double avgScore, double totalDurationSeconds,
                                    double priceAtDetection, Double resistanceLevel, int confidenceScore) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.type = type;
        this.consecutiveCount = consecutiveCount;
        this.avgScore = avgScore;
        this.totalDurationSeconds = totalDurationSeconds;
        this.priceAtDetection = priceAtDetection;
        this.resistanceLevel = resistanceLevel;
        this.confidenceScore = confidenceScore;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public String getType() { return type; }
    public int getConsecutiveCount() { return consecutiveCount; }
    public double getAvgScore() { return avgScore; }
    public double getTotalDurationSeconds() { return totalDurationSeconds; }
    public double getPriceAtDetection() { return priceAtDetection; }
    public Double getResistanceLevel() { return resistanceLevel; }
    public int getConfidenceScore() { return confidenceScore; }
}
