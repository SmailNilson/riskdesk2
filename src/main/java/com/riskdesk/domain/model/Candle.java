package com.riskdesk.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public class Candle {

    private Long id;
    private Instrument instrument;
    private String timeframe; // "1m", "5m", "10m", "1h", "4h", "1d"
    private String contractMonth;
    private Instant timestamp;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private long volume;

    public Candle() {}

    public Candle(Instrument instrument, String timeframe, Instant timestamp,
                  BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        this(instrument, timeframe, null, timestamp, open, high, low, close, volume);
    }

    public Candle(Instrument instrument, String timeframe, String contractMonth, Instant timestamp,
                  BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        this.instrument = instrument;
        this.timeframe = timeframe;
        this.contractMonth = contractMonth;
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
