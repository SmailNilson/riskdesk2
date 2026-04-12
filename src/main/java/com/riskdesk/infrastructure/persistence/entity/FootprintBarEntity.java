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
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Persists footprint bar data from the order flow subsystem (UC-OF-011).
 * Each bar aggregates buy/sell volume at each price level for a given timeframe.
 * The profileJson column stores the per-price-level volume breakdown as JSON.
 * 30-day retention (same as tick_log), purged via TickLogPort.
 */
@Entity
@Table(
    name = "order_flow_footprint_bars",
    indexes = {
        @Index(name = "idx_of_footprint_instrument_tf_ts", columnList = "instrument, timeframe, barTimestamp")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_footprint_instrument_tf_ts", columnNames = {"instrument", "timeframe", "barTimestamp"})
    }
)
public class FootprintBarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(nullable = false)
    private Instant barTimestamp;

    @Column(nullable = false)
    private double pocPrice;

    @Column(nullable = false)
    private long totalBuyVolume;

    @Column(nullable = false)
    private long totalSellVolume;

    @Column(nullable = false)
    private long totalDelta;

    /** Per-price-level volume profile serialized as JSON. */
    @Column(columnDefinition = "TEXT")
    private String profileJson;

    protected FootprintBarEntity() {}

    public FootprintBarEntity(Instrument instrument, String timeframe, Instant barTimestamp,
                              double pocPrice, long totalBuyVolume, long totalSellVolume,
                              long totalDelta, String profileJson) {
        this.instrument = instrument;
        this.timeframe = timeframe;
        this.barTimestamp = barTimestamp;
        this.pocPrice = pocPrice;
        this.totalBuyVolume = totalBuyVolume;
        this.totalSellVolume = totalSellVolume;
        this.totalDelta = totalDelta;
        this.profileJson = profileJson;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public String getTimeframe() { return timeframe; }
    public Instant getBarTimestamp() { return barTimestamp; }
    public double getPocPrice() { return pocPrice; }
    public long getTotalBuyVolume() { return totalBuyVolume; }
    public long getTotalSellVolume() { return totalSellVolume; }
    public long getTotalDelta() { return totalDelta; }
    public String getProfileJson() { return profileJson; }
}
