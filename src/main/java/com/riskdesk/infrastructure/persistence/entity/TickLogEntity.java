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
 * Persists individual trade ticks from IBKR tick-by-tick AllLast feed.
 * Used for calibration of absorption, spoofing, and flash crash thresholds (UC-OF-008).
 * High-volume table — partitioned by date recommended, 30-day retention.
 */
@Entity
@Table(
    name = "tick_log",
    indexes = {
        @Index(name = "idx_tick_log_instrument_ts", columnList = "instrument, timestamp")
    }
)
public class TickLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Instrument instrument;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private double price;

    @Column(nullable = false)
    private long size;

    /** Lee-Ready classification: BUY, SELL, or UNCLASSIFIED. */
    @Column(nullable = false, length = 14)
    private String classification;

    @Column(nullable = false)
    private double bidPrice;

    @Column(nullable = false)
    private double askPrice;

    @Column(length = 20)
    private String exchange;

    protected TickLogEntity() {}

    public TickLogEntity(Instrument instrument, Instant timestamp, double price, long size,
                         String classification, double bidPrice, double askPrice, String exchange) {
        this.instrument = instrument;
        this.timestamp = timestamp;
        this.price = price;
        this.size = size;
        this.classification = classification;
        this.bidPrice = bidPrice;
        this.askPrice = askPrice;
        this.exchange = exchange;
    }

    public Long getId() { return id; }
    public Instrument getInstrument() { return instrument; }
    public Instant getTimestamp() { return timestamp; }
    public double getPrice() { return price; }
    public long getSize() { return size; }
    public String getClassification() { return classification; }
    public double getBidPrice() { return bidPrice; }
    public double getAskPrice() { return askPrice; }
    public String getExchange() { return exchange; }
}
