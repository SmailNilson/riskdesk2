package com.riskdesk.domain.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "candles",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_candle_instrument_tf_ts", columnNames = {"instrument", "timeframe", "timestamp"})
    },
    indexes = {
        @Index(name = "idx_candle_instrument_tf", columnList = "instrument, timeframe, timestamp")
    }
)
public class Candle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Instrument instrument;

    @Column(nullable = false)
    private String timeframe; // "1m", "5m", "10m", "1h", "4h", "1d"

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, precision = 12, scale = 5)
    private BigDecimal open;

    @Column(nullable = false, precision = 12, scale = 5)
    private BigDecimal high;

    @Column(nullable = false, precision = 12, scale = 5)
    private BigDecimal low;

    @Column(nullable = false, precision = 12, scale = 5)
    private BigDecimal close;

    @Column(nullable = false)
    private long volume;

    public Candle() {}

    public Candle(Instrument instrument, String timeframe, Instant timestamp,
                  BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        this.instrument = instrument;
        this.timeframe = timeframe;
        this.timestamp = timestamp;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    // --- Helpers ---

    public boolean isBullish() { return close.compareTo(open) > 0; }
    public boolean isBearish() { return close.compareTo(open) < 0; }

    public BigDecimal body() { return close.subtract(open).abs(); }
    public BigDecimal range() { return high.subtract(low); }

    public BigDecimal upperWick() {
        return high.subtract(isBullish() ? close : open);
    }

    public BigDecimal lowerWick() {
        return (isBullish() ? open : close).subtract(low);
    }

    // --- Getters & Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instrument getInstrument() { return instrument; }
    public void setInstrument(Instrument instrument) { this.instrument = instrument; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }
    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }
    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }
    public BigDecimal getClose() { return close; }
    public void setClose(BigDecimal close) { this.close = close; }
    public long getVolume() { return volume; }
    public void setVolume(long volume) { this.volume = volume; }
}
