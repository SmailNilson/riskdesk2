package com.riskdesk.infrastructure.persistence.entity;

import com.riskdesk.domain.model.Instrument;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for flash crash detection thresholds per instrument.
 * Each instrument has at most one configuration row.
 */
@Entity
@Table(name = "flash_crash_config")
public class FlashCrashConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private double velocityThreshold;

    @Column(nullable = false)
    private double delta5sThreshold;

    @Column(nullable = false)
    private double accelerationThreshold;

    @Column(nullable = false)
    private double depthImbalanceThreshold;

    @Column(nullable = false)
    private double volumeSpikeMultiplier;

    @Column(nullable = false)
    private int conditionsRequired = 3;

    @Column(nullable = false)
    private Instant updatedAt;

    protected FlashCrashConfigEntity() {}

    public FlashCrashConfigEntity(Instrument instrument,
                                  double velocityThreshold,
                                  double delta5sThreshold,
                                  double accelerationThreshold,
                                  double depthImbalanceThreshold,
                                  double volumeSpikeMultiplier,
                                  int conditionsRequired,
                                  Instant updatedAt) {
        this.instrument = instrument;
        this.velocityThreshold = velocityThreshold;
        this.delta5sThreshold = delta5sThreshold;
        this.accelerationThreshold = accelerationThreshold;
        this.depthImbalanceThreshold = depthImbalanceThreshold;
        this.volumeSpikeMultiplier = volumeSpikeMultiplier;
        this.conditionsRequired = conditionsRequired;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }

    public Instrument getInstrument() { return instrument; }
    public void setInstrument(Instrument instrument) { this.instrument = instrument; }

    public double getVelocityThreshold() { return velocityThreshold; }
    public void setVelocityThreshold(double velocityThreshold) { this.velocityThreshold = velocityThreshold; }

    public double getDelta5sThreshold() { return delta5sThreshold; }
    public void setDelta5sThreshold(double delta5sThreshold) { this.delta5sThreshold = delta5sThreshold; }

    public double getAccelerationThreshold() { return accelerationThreshold; }
    public void setAccelerationThreshold(double accelerationThreshold) { this.accelerationThreshold = accelerationThreshold; }

    public double getDepthImbalanceThreshold() { return depthImbalanceThreshold; }
    public void setDepthImbalanceThreshold(double depthImbalanceThreshold) { this.depthImbalanceThreshold = depthImbalanceThreshold; }

    public double getVolumeSpikeMultiplier() { return volumeSpikeMultiplier; }
    public void setVolumeSpikeMultiplier(double volumeSpikeMultiplier) { this.volumeSpikeMultiplier = volumeSpikeMultiplier; }

    public int getConditionsRequired() { return conditionsRequired; }
    public void setConditionsRequired(int conditionsRequired) { this.conditionsRequired = conditionsRequired; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
