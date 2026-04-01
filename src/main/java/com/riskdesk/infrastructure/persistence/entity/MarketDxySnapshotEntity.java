package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    name = "market_dxy_snapshots",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_market_dxy_snapshot_timestamp", columnNames = {"timestamp"})
    },
    indexes = {
        @Index(name = "idx_market_dxy_snapshot_timestamp_desc", columnList = "timestamp")
    }
)
public class MarketDxySnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal eurusd;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal usdjpy;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal gbpusd;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal usdcad;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal usdsek;

    @Column(nullable = false, precision = 19, scale = 8)
    private BigDecimal usdchf;

    @Column(name = "dxy_value", nullable = false, precision = 19, scale = 8)
    private BigDecimal dxyValue;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "is_complete", nullable = false)
    private boolean complete;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public BigDecimal getEurusd() {
        return eurusd;
    }

    public void setEurusd(BigDecimal eurusd) {
        this.eurusd = eurusd;
    }

    public BigDecimal getUsdjpy() {
        return usdjpy;
    }

    public void setUsdjpy(BigDecimal usdjpy) {
        this.usdjpy = usdjpy;
    }

    public BigDecimal getGbpusd() {
        return gbpusd;
    }

    public void setGbpusd(BigDecimal gbpusd) {
        this.gbpusd = gbpusd;
    }

    public BigDecimal getUsdcad() {
        return usdcad;
    }

    public void setUsdcad(BigDecimal usdcad) {
        this.usdcad = usdcad;
    }

    public BigDecimal getUsdsek() {
        return usdsek;
    }

    public void setUsdsek(BigDecimal usdsek) {
        this.usdsek = usdsek;
    }

    public BigDecimal getUsdchf() {
        return usdchf;
    }

    public void setUsdchf(BigDecimal usdchf) {
        this.usdchf = usdchf;
    }

    public BigDecimal getDxyValue() {
        return dxyValue;
    }

    public void setDxyValue(BigDecimal dxyValue) {
        this.dxyValue = dxyValue;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }
}
