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
public class CandleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Instrument instrument;

    @Column(nullable = false)
    private String timeframe;

    @Column(length = 6)
    private String contractMonth;

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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instrument getInstrument() { return instrument; }
    public void setInstrument(Instrument instrument) { this.instrument = instrument; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public String getContractMonth() { return contractMonth; }
    public void setContractMonth(String contractMonth) { this.contractMonth = contractMonth; }
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
