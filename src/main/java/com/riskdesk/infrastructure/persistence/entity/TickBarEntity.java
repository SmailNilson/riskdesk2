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
 * Persists a completed tick-chart bar so the chart survives a backend redeploy.
 * Reloaded into the in-memory ring buffer on startup by the tick-bar adapter.
 *
 * <p>{@code openTime}/{@code closeTime} are kept as epoch-second BIGINTs for an exact
 * round-trip back to the frontend DTO; {@code closeAt} is the same close instant as a
 * TIMESTAMPTZ, used only for indexed retention purges. Low volume (one row per N trades,
 * capped per instrument by the ring-buffer depth) — short retention.</p>
 */
@Entity
@Table(
    name = "tick_bar",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_tick_bar_inst_size_seq", columnNames = {"instrument", "ticks_per_bar", "seq"})
    },
    indexes = {
        @Index(name = "idx_tick_bar_inst_size_seq", columnList = "instrument, ticks_per_bar, seq"),
        @Index(name = "idx_tick_bar_close_at", columnList = "close_at")
    }
)
public class TickBarEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(name = "ticks_per_bar", nullable = false)
    private int ticksPerBar;

    @Column(nullable = false)
    private long seq;

    @Column(name = "open_time", nullable = false)
    private long openTime;

    @Column(name = "close_time", nullable = false)
    private long closeTime;

    @Column(name = "close_at", nullable = false)
    private Instant closeAt;

    @Column(name = "open_price", nullable = false)
    private double open;

    @Column(name = "high_price", nullable = false)
    private double high;

    @Column(name = "low_price", nullable = false)
    private double low;

    @Column(name = "close_price", nullable = false)
    private double close;

    @Column(nullable = false)
    private long volume;

    @Column(name = "buy_volume", nullable = false)
    private long buyVolume;

    @Column(name = "sell_volume", nullable = false)
    private long sellVolume;

    @Column(nullable = false)
    private long delta;

    @Column(name = "tick_count", nullable = false)
    private int tickCount;

    protected TickBarEntity() {}

    public TickBarEntity(Instrument instrument, int ticksPerBar, long seq,
                         long openTime, long closeTime,
                         double open, double high, double low, double close,
                         long volume, long buyVolume, long sellVolume, long delta, int tickCount) {
        this.instrument = instrument;
        this.ticksPerBar = ticksPerBar;
        this.seq = seq;
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.closeAt = Instant.ofEpochSecond(closeTime);
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.buyVolume = buyVolume;
        this.sellVolume = sellVolume;
        this.delta = delta;
        this.tickCount = tickCount;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public int getTicksPerBar() { return ticksPerBar; }
    public long getSeq() { return seq; }
    public long getOpenTime() { return openTime; }
    public long getCloseTime() { return closeTime; }
    public Instant getCloseAt() { return closeAt; }
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; }
    public long getBuyVolume() { return buyVolume; }
    public long getSellVolume() { return sellVolume; }
    public long getDelta() { return delta; }
    public int getTickCount() { return tickCount; }
}
