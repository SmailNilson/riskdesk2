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
 * Periodic snapshot of market depth (order book state) for calibration (UC-OF-008).
 * Persisted every ~10 seconds per instrument (not every 500ms — that would be excessive).
 * 30-day retention, purged alongside tick_log.
 */
@Entity
@Table(
    name = "depth_snapshot",
    indexes = {
        @Index(name = "idx_depth_snap_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class DepthSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private long totalBidSize;

    @Column(nullable = false)
    private long totalAskSize;

    /** (totalBid - totalAsk) / (totalBid + totalAsk). Range: -1.0 to 1.0. */
    @Column(nullable = false)
    private double depthImbalance;

    @Column(nullable = false)
    private double bestBid;

    @Column(nullable = false)
    private double bestAsk;

    @Column(nullable = false)
    private double spread;

    /** Nullable: price of largest bid cluster (> 5x average), null if no wall detected. */
    private Double bidWallPrice;
    private Long bidWallSize;

    /** Nullable: price of largest ask cluster (> 5x average), null if no wall detected. */
    private Double askWallPrice;
    private Long askWallSize;

    protected DepthSnapshotEntity() {}

    public DepthSnapshotEntity(Instrument instrument, Instant timestamp,
                               long totalBidSize, long totalAskSize, double depthImbalance,
                               double bestBid, double bestAsk, double spread) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.totalBidSize = totalBidSize;
        this.totalAskSize = totalAskSize;
        this.depthImbalance = depthImbalance;
        this.bestBid = bestBid;
        this.bestAsk = bestAsk;
        this.spread = spread;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public long getTotalBidSize() { return totalBidSize; }
    public long getTotalAskSize() { return totalAskSize; }
    public double getDepthImbalance() { return depthImbalance; }
    public double getBestBid() { return bestBid; }
    public double getBestAsk() { return bestAsk; }
    public double getSpread() { return spread; }
    public Double getBidWallPrice() { return bidWallPrice; }
    public void setBidWallPrice(Double bidWallPrice) { this.bidWallPrice = bidWallPrice; }
    public Long getBidWallSize() { return bidWallSize; }
    public void setBidWallSize(Long bidWallSize) { this.bidWallSize = bidWallSize; }
    public Double getAskWallPrice() { return askWallPrice; }
    public void setAskWallPrice(Double askWallPrice) { this.askWallPrice = askWallPrice; }
    public Long getAskWallSize() { return askWallSize; }
    public void setAskWallSize(Long askWallSize) { this.askWallSize = askWallSize; }
}
