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
 * Persists absorption detection events from the order flow subsystem (UC-OF-004).
 * High delta + stable price + high volume = institutional limit orders absorbing aggressive flow.
 * 90-day retention, purged by OrderFlowEventPersistenceService.
 */
@Entity
@Table(
    name = "order_flow_absorption_events",
    indexes = {
        @Index(name = "idx_of_absorption_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class AbsorptionEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant timestamp;

    /** BULLISH_ABSORPTION or BEARISH_ABSORPTION. */
    @Column(nullable = false, length = 25)
    private String side;

    @Column(nullable = false)
    private double absorptionScore;

    @Column(nullable = false)
    private long aggressiveDelta;

    @Column(nullable = false)
    private double priceMoveTicks;

    @Column(nullable = false)
    private long totalVolume;

    protected AbsorptionEventEntity() {}

    public AbsorptionEventEntity(Instrument instrument, Instant timestamp, String side,
                                 double absorptionScore, long aggressiveDelta,
                                 double priceMoveTicks, long totalVolume) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.side = side;
        this.absorptionScore = absorptionScore;
        this.aggressiveDelta = aggressiveDelta;
        this.priceMoveTicks = priceMoveTicks;
        this.totalVolume = totalVolume;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public String getSide() { return side; }
    public double getAbsorptionScore() { return absorptionScore; }
    public long getAggressiveDelta() { return aggressiveDelta; }
    public double getPriceMoveTicks() { return priceMoveTicks; }
    public long getTotalVolume() { return totalVolume; }
}
