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
 * One simulated trade of the CVD-divergence paper loop (UC-OF-CVD-PAPER):
 * entry at divergence detection (RTH only), exit when the DIV badge window
 * lapses, the divergence flips direction, or RTH ends. Pure paper — no broker
 * order is ever derived from this table.
 */
@Entity
@Table(
    name = "cvd_divergence_paper_trades",
    indexes = {
        @Index(name = "idx_cvddiv_paper_instr_status", columnList = "instrument, status"),
        @Index(name = "idx_cvddiv_paper_instr_entry", columnList = "instrument, entryTime")
    }
)
public class CvdDivergencePaperTradeEntity {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    public static final String REASON_BADGE_EXPIRED = "BADGE_EXPIRED";
    public static final String REASON_FLIPPED = "FLIPPED";
    public static final String REASON_SESSION_END = "SESSION_END";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    /** LONG (bullish divergence) or SHORT (bearish divergence). */
    @Column(nullable = false, length = 5)
    private String direction;

    /** Divergence type that opened the trade. */
    @Column(nullable = false, length = 20)
    private String divergenceType;

    @Column(nullable = false)
    private Instant entryTime;

    @Column(nullable = false)
    private double entryPrice;

    /** Last divergence event time — same-direction events refresh it (badge refresh). */
    @Column(nullable = false)
    private Instant lastSignalTime;

    @Column(nullable = false, length = 6)
    private String status;

    private Instant exitTime;

    private Double exitPrice;

    /** BADGE_EXPIRED, FLIPPED or SESSION_END. */
    @Column(length = 15)
    private String closeReason;

    /** Signed points: (exit − entry) for LONG, (entry − exit) for SHORT. */
    private Double pnlPoints;

    /** PnL in contract currency for one contract (points × tickValue / tickSize). */
    private Double pnlCurrency;

    // Opening-signal pivot snapshot, kept for the event study.
    @Column(nullable = false)
    private double prevPivotPrice;

    @Column(nullable = false)
    private double newPivotPrice;

    @Column(nullable = false)
    private long prevPivotCvd;

    @Column(nullable = false)
    private long newPivotCvd;

    @Column(nullable = false)
    private Instant pivotTimestamp;

    protected CvdDivergencePaperTradeEntity() {}

    public CvdDivergencePaperTradeEntity(Instrument instrument, String direction, String divergenceType,
                                         Instant entryTime, double entryPrice,
                                         double prevPivotPrice, double newPivotPrice,
                                         long prevPivotCvd, long newPivotCvd, Instant pivotTimestamp) {
        this.instrument = instrument;
        this.direction = direction;
        this.divergenceType = divergenceType;
        this.entryTime = entryTime;
        this.entryPrice = entryPrice;
        this.lastSignalTime = entryTime;
        this.status = STATUS_OPEN;
        this.prevPivotPrice = prevPivotPrice;
        this.newPivotPrice = newPivotPrice;
        this.prevPivotCvd = prevPivotCvd;
        this.newPivotCvd = newPivotCvd;
        this.pivotTimestamp = pivotTimestamp;
    }

    public void refreshSignal(Instant signalTime) {
        this.lastSignalTime = signalTime;
    }

    public void close(Instant exitTime, double exitPrice, String closeReason,
                      double pnlPoints, double pnlCurrency) {
        this.status = STATUS_CLOSED;
        this.exitTime = exitTime;
        this.exitPrice = exitPrice;
        this.closeReason = closeReason;
        this.pnlPoints = pnlPoints;
        this.pnlCurrency = pnlCurrency;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public String getDirection() { return direction; }
    public String getDivergenceType() { return divergenceType; }
    public Instant getEntryTime() { return entryTime; }
    public double getEntryPrice() { return entryPrice; }
    public Instant getLastSignalTime() { return lastSignalTime; }
    public String getStatus() { return status; }
    public Instant getExitTime() { return exitTime; }
    public Double getExitPrice() { return exitPrice; }
    public String getCloseReason() { return closeReason; }
    public Double getPnlPoints() { return pnlPoints; }
    public Double getPnlCurrency() { return pnlCurrency; }
    public double getPrevPivotPrice() { return prevPivotPrice; }
    public double getNewPivotPrice() { return newPivotPrice; }
    public long getPrevPivotCvd() { return prevPivotCvd; }
    public long getNewPivotCvd() { return newPivotCvd; }
    public Instant getPivotTimestamp() { return pivotTimestamp; }
}
