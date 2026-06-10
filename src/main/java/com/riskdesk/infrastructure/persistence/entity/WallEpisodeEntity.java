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
 * Persists closed order-book wall episodes (UC-OF-012) — the traceability trail of
 * large resting orders: where they appeared, how big they grew, how long they sat,
 * and how they ended (CONSUMED / PULLED / FADED / OUT_OF_RANGE).
 * 90-day retention, purged by OrderFlowEventPersistenceService.
 */
@Entity
@Table(
    name = "order_flow_wall_episodes",
    indexes = {
        @Index(name = "idx_of_wall_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class WallEpisodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    /** When the episode was finalized (= endedAt). Named for the shared purge contract. */
    @Column(nullable = false)
    private Instant timestamp;

    /** BID or ASK. */
    @Column(nullable = false, length = 5)
    private String side;

    @Column(nullable = false)
    private double price;

    @Column(nullable = false)
    private long initialSize;

    @Column(nullable = false)
    private long maxSize;

    @Column(nullable = false)
    private long lastSize;

    @Column(nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private double durationSeconds;

    /** CONSUMED / PULLED / FADED / OUT_OF_RANGE. */
    @Column(nullable = false, length = 15)
    private String outcome;

    /** Distance (ticks) from the wall to the same-side best at finalization. */
    @Column(nullable = false)
    private double endDistanceTicks;

    protected WallEpisodeEntity() {}

    public WallEpisodeEntity(Instrument instrument, Instant timestamp, String side, double price,
                             long initialSize, long maxSize, long lastSize, Instant firstSeenAt,
                             double durationSeconds, String outcome, double endDistanceTicks) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.side = side;
        this.price = price;
        this.initialSize = initialSize;
        this.maxSize = maxSize;
        this.lastSize = lastSize;
        this.firstSeenAt = firstSeenAt;
        this.durationSeconds = durationSeconds;
        this.outcome = outcome;
        this.endDistanceTicks = endDistanceTicks;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public String getSide() { return side; }
    public double getPrice() { return price; }
    public long getInitialSize() { return initialSize; }
    public long getMaxSize() { return maxSize; }
    public long getLastSize() { return lastSize; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public double getDurationSeconds() { return durationSeconds; }
    public String getOutcome() { return outcome; }
    public double getEndDistanceTicks() { return endDistanceTicks; }
}
