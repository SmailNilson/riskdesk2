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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Append-only record of a live-analysis verdict.
 * <p>
 * Stores the snapshot AND the verdict as JSON for full replay determinism:
 * with the snapshot we can re-score with new weights, with the verdict we can
 * compare expected vs realised.
 */
@Entity
@Table(
    name = "live_verdict_records",
    indexes = {
        @Index(name = "idx_lvr_instrument_tf_decision",
               columnList = "instrument, timeframe, decisionTimestamp")
    }
)
public class VerdictRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false, length = 8)
    private String timeframe;

    @Column(nullable = false)
    private Instant decisionTimestamp;

    @Column(nullable = false)
    private Instant captureTimestamp;

    @Column(nullable = false)
    private int scoringEngineVersion;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal currentPrice;

    @Column(nullable = false, length = 8)
    private String primaryDirection;

    @Column(nullable = false)
    private int confidence;

    @Column(nullable = false)
    private double structureScore;

    @Column(nullable = false)
    private double orderFlowScore;

    @Column(nullable = false)
    private double momentumScore;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String verdictJson;

    protected VerdictRecordEntity() {}

    public VerdictRecordEntity(Instrument instrument, String timeframe,
                                Instant decisionTimestamp, Instant captureTimestamp,
                                int scoringEngineVersion, BigDecimal currentPrice,
                                String primaryDirection, int confidence,
                                double structureScore, double orderFlowScore, double momentumScore,
                                String snapshotJson, String verdictJson) {
        this.instrument = instrument;
        this.timeframe = timeframe;
        this.decisionTimestamp = decisionTimestamp;
        this.captureTimestamp = captureTimestamp;
        this.scoringEngineVersion = scoringEngineVersion;
        this.currentPrice = currentPrice;
        this.primaryDirection = primaryDirection;
        this.confidence = confidence;
        this.structureScore = structureScore;
        this.orderFlowScore = orderFlowScore;
        this.momentumScore = momentumScore;
        this.snapshotJson = snapshotJson;
        this.verdictJson = verdictJson;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public String getTimeframe() { return timeframe; }
    public Instant getDecisionTimestamp() { return decisionTimestamp; }
    public Instant getCaptureTimestamp() { return captureTimestamp; }
    public int getScoringEngineVersion() { return scoringEngineVersion; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
    public String getPrimaryDirection() { return primaryDirection; }
    public int getConfidence() { return confidence; }
    public double getStructureScore() { return structureScore; }
    public double getOrderFlowScore() { return orderFlowScore; }
    public double getMomentumScore() { return momentumScore; }
    public String getSnapshotJson() { return snapshotJson; }
    public String getVerdictJson() { return verdictJson; }
}
