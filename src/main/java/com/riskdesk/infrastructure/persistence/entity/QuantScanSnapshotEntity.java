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
 * One row per Quant 7-Gates scan tick — the raw inputs the gates saw plus the
 * key outputs (scores, pattern, per-gate verdicts as JSON). Written
 * best-effort by {@code QuantGateService} after every evaluation, including
 * non-signal scans (a signals-only log would be survivorship-biased for
 * backtests). 90-day retention, purged by {@code OrderFlowEventPersistenceService}.
 */
@Entity
@Table(
    name = "quant_scan_snapshots",
    indexes = {
        @Index(name = "idx_quant_scan_instrument_ts", columnList = "instrument, scannedAt")
    }
)
public class QuantScanSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant scannedAt;

    @Column
    private Double price;

    @Column(length = 30)
    private String priceSource;

    /** Delta as seen by the gates — null when the feed was stale (abstain). */
    @Column
    private Double delta;

    @Column
    private Double buyRatioPct;

    /** Provenance of the raw delta window (REAL_TICKS / CLV_ESTIMATED / …). */
    @Column(length = 30)
    private String deltaSource;

    @Column(nullable = false)
    private int absFreshTotal;

    @Column(nullable = false)
    private int absBull8Count;

    @Column(nullable = false)
    private int absBear8Count;

    @Column(nullable = false)
    private double absMaxScore;

    @Column(length = 8)
    private String dominantSide;

    @Column(length = 20)
    private String distType;

    @Column
    private Integer distConf;

    @Column(length = 30)
    private String cycleType;

    @Column(length = 30)
    private String cyclePhase;

    @Column(nullable = false)
    private int score;

    @Column(nullable = false)
    private int longScore;

    @Column(length = 40)
    private String patternType;

    @Column(length = 80)
    private String patternLabel;

    @Column(length = 10)
    private String patternConfidence;

    /** Canonical SHORT-view action; the LONG view is the deterministic mirror. */
    @Column(length = 10)
    private String patternActionShort;

    /** JSON object: gate name → "PASS|FAIL|ABSTAIN — reason". */
    @Column(columnDefinition = "text")
    private String gatesJson;

    protected QuantScanSnapshotEntity() {}

    public QuantScanSnapshotEntity(Instrument instrument, Instant scannedAt,
                                   Double price, String priceSource,
                                   Double delta, Double buyRatioPct, String deltaSource,
                                   int absFreshTotal, int absBull8Count, int absBear8Count,
                                   double absMaxScore, String dominantSide,
                                   String distType, Integer distConf,
                                   String cycleType, String cyclePhase,
                                   int score, int longScore,
                                   String patternType, String patternLabel,
                                   String patternConfidence, String patternActionShort,
                                   String gatesJson) {
        this.instrument = instrument;
        this.scannedAt = scannedAt;
        this.price = price;
        this.priceSource = priceSource;
        this.delta = delta;
        this.buyRatioPct = buyRatioPct;
        this.deltaSource = deltaSource;
        this.absFreshTotal = absFreshTotal;
        this.absBull8Count = absBull8Count;
        this.absBear8Count = absBear8Count;
        this.absMaxScore = absMaxScore;
        this.dominantSide = dominantSide;
        this.distType = distType;
        this.distConf = distConf;
        this.cycleType = cycleType;
        this.cyclePhase = cyclePhase;
        this.score = score;
        this.longScore = longScore;
        this.patternType = patternType;
        this.patternLabel = patternLabel;
        this.patternConfidence = patternConfidence;
        this.patternActionShort = patternActionShort;
        this.gatesJson = gatesJson;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getScannedAt() { return scannedAt; }
    public Double getPrice() { return price; }
    public String getPriceSource() { return priceSource; }
    public Double getDelta() { return delta; }
    public Double getBuyRatioPct() { return buyRatioPct; }
    public String getDeltaSource() { return deltaSource; }
    public int getAbsFreshTotal() { return absFreshTotal; }
    public int getAbsBull8Count() { return absBull8Count; }
    public int getAbsBear8Count() { return absBear8Count; }
    public double getAbsMaxScore() { return absMaxScore; }
    public String getDominantSide() { return dominantSide; }
    public String getDistType() { return distType; }
    public Integer getDistConf() { return distConf; }
    public String getCycleType() { return cycleType; }
    public String getCyclePhase() { return cyclePhase; }
    public int getScore() { return score; }
    public int getLongScore() { return longScore; }
    public String getPatternType() { return patternType; }
    public String getPatternLabel() { return patternLabel; }
    public String getPatternConfidence() { return patternConfidence; }
    public String getPatternActionShort() { return patternActionShort; }
    public String getGatesJson() { return gatesJson; }
}
