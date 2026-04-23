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
 * Persists aggressive momentum burst events — the inverse of absorption.
 * 90-day retention, purged by OrderFlowEventPersistenceService.
 */
@Entity
@Table(
    name = "order_flow_momentum_events",
    indexes = {
        @Index(name = "idx_of_momentum_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class MomentumEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant timestamp;

    /** BULLISH_MOMENTUM or BEARISH_MOMENTUM. */
    @Column(nullable = false, length = 20)
    private String side;

    @Column(nullable = false)
    private double momentumScore;

    @Column(nullable = false)
    private long aggressiveDelta;

    @Column(nullable = false)
    private double priceMoveTicks;

    @Column(nullable = false)
    private double priceMovePoints;

    @Column(nullable = false)
    private long totalVolume;

    protected MomentumEventEntity() {}

    public MomentumEventEntity(Instrument instrument, Instant timestamp, String side,
                                double momentumScore, long aggressiveDelta,
                                double priceMoveTicks, double priceMovePoints, long totalVolume) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.side = side;
        this.momentumScore = momentumScore;
        this.aggressiveDelta = aggressiveDelta;
        this.priceMoveTicks = priceMoveTicks;
        this.priceMovePoints = priceMovePoints;
        this.totalVolume = totalVolume;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public String getSide() { return side; }
    public double getMomentumScore() { return momentumScore; }
    public long getAggressiveDelta() { return aggressiveDelta; }
    public double getPriceMoveTicks() { return priceMoveTicks; }
    public double getPriceMovePoints() { return priceMovePoints; }
    public long getTotalVolume() { return totalVolume; }
}
