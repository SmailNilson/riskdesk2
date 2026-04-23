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
 * Persists smart-money cycle events — chained distribution / momentum / accumulation phases.
 * 90-day retention, purged by OrderFlowEventPersistenceService.
 */
@Entity
@Table(
    name = "order_flow_cycle_events",
    indexes = {
        @Index(name = "idx_of_cycle_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class CycleEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant timestamp;

    /** BEARISH_CYCLE or BULLISH_CYCLE. */
    @Column(nullable = false, length = 20)
    private String cycleType;

    /** PHASE_1, PHASE_2, PHASE_3, COMPLETE. */
    @Column(nullable = false, length = 15)
    private String currentPhase;

    @Column(nullable = false)
    private double priceAtPhase1;

    @Column(nullable = true)
    private Double priceAtPhase2;

    @Column(nullable = true)
    private Double priceAtPhase3;

    @Column(nullable = false)
    private double totalPriceMove;

    @Column(nullable = false)
    private double totalDurationMinutes;

    @Column(nullable = false)
    private int confidence;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = true)
    private Instant completedAt;

    protected CycleEventEntity() {}

    public CycleEventEntity(Instrument instrument, Instant timestamp, String cycleType,
                             String currentPhase, double priceAtPhase1, Double priceAtPhase2,
                             Double priceAtPhase3, double totalPriceMove, double totalDurationMinutes,
                             int confidence, Instant startedAt, Instant completedAt) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.cycleType = cycleType;
        this.currentPhase = currentPhase;
        this.priceAtPhase1 = priceAtPhase1;
        this.priceAtPhase2 = priceAtPhase2;
        this.priceAtPhase3 = priceAtPhase3;
        this.totalPriceMove = totalPriceMove;
        this.totalDurationMinutes = totalDurationMinutes;
        this.confidence = confidence;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public String getCycleType() { return cycleType; }
    public String getCurrentPhase() { return currentPhase; }
    public double getPriceAtPhase1() { return priceAtPhase1; }
    public Double getPriceAtPhase2() { return priceAtPhase2; }
    public Double getPriceAtPhase3() { return priceAtPhase3; }
    public double getTotalPriceMove() { return totalPriceMove; }
    public double getTotalDurationMinutes() { return totalDurationMinutes; }
    public int getConfidence() { return confidence; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
