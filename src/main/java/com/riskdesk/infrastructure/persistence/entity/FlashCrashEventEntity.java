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
 * Persists flash crash FSM phase transitions from the order flow subsystem (UC-OF-006).
 * Each row represents a state change in the crash detection finite state machine.
 * 90-day retention, purged by OrderFlowEventPersistenceService.
 */
@Entity
@Table(
    name = "order_flow_flash_crash_events",
    indexes = {
        @Index(name = "idx_of_crash_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class FlashCrashEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant timestamp;

    /** CrashPhase name: NORMAL, INITIATING, ACCELERATING, DECELERATING, REVERSING. */
    @Column(nullable = false, length = 20)
    private String previousPhase;

    @Column(nullable = false, length = 20)
    private String currentPhase;

    @Column(nullable = false)
    private int conditionsMet;

    @Column(nullable = false)
    private double reversalScore;

    protected FlashCrashEventEntity() {}

    public FlashCrashEventEntity(Instrument instrument, Instant timestamp,
                                 String previousPhase, String currentPhase,
                                 int conditionsMet, double reversalScore) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.previousPhase = previousPhase;
        this.currentPhase = currentPhase;
        this.conditionsMet = conditionsMet;
        this.reversalScore = reversalScore;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public String getPreviousPhase() { return previousPhase; }
    public String getCurrentPhase() { return currentPhase; }
    public int getConditionsMet() { return conditionsMet; }
    public double getReversalScore() { return reversalScore; }
}
