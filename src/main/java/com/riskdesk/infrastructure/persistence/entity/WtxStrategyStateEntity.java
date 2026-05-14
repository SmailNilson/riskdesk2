package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "wtx_strategy_states")
public class WtxStrategyStateEntity {

    @Id
    @Column(nullable = false, length = 20)
    private String instrument;

    @Column(nullable = false, length = 10)
    private String currentDirection; // FLAT, LONG, SHORT

    @Column(precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal entryQty;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal dayStartEquity;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal currentEquity;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal dailyRealizedPnl;

    @Column(nullable = false)
    private boolean maxLossHit;

    @Column
    private Instant lastCandleTs;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(length = 20)
    private String activeProfile; // BASELINE | SESSION_ATR | HTF | STRICT

    /**
     * Nullable (boxed) on purpose: when this column is introduced by Hibernate
     * {@code ddl-auto=update} on an already-populated table, a {@code NOT NULL}
     * boolean add is rejected by PostgreSQL and aborts the whole migration —
     * which would leave every new WTX column missing. Kept nullable so the
     * ALTER succeeds; legacy rows read back as {@code null} → treated as false
     * by the adapter. New writes always persist a concrete true/false.
     */
    @Column
    private Boolean autoExecutionEnabled;

    @Column(precision = 20, scale = 8)
    private BigDecimal entryAtr;

    @Column(precision = 20, scale = 8)
    private BigDecimal bestFavorablePrice;

    @Column(precision = 20, scale = 8)
    private BigDecimal trailingStopPrice;

    public WtxStrategyStateEntity() {}

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getCurrentDirection() { return currentDirection; }
    public void setCurrentDirection(String currentDirection) { this.currentDirection = currentDirection; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getEntryQty() { return entryQty; }
    public void setEntryQty(BigDecimal entryQty) { this.entryQty = entryQty; }

    public BigDecimal getDayStartEquity() { return dayStartEquity; }
    public void setDayStartEquity(BigDecimal dayStartEquity) { this.dayStartEquity = dayStartEquity; }

    public BigDecimal getCurrentEquity() { return currentEquity; }
    public void setCurrentEquity(BigDecimal currentEquity) { this.currentEquity = currentEquity; }

    public BigDecimal getDailyRealizedPnl() { return dailyRealizedPnl; }
    public void setDailyRealizedPnl(BigDecimal dailyRealizedPnl) { this.dailyRealizedPnl = dailyRealizedPnl; }

    public boolean isMaxLossHit() { return maxLossHit; }
    public void setMaxLossHit(boolean maxLossHit) { this.maxLossHit = maxLossHit; }

    public Instant getLastCandleTs() { return lastCandleTs; }
    public void setLastCandleTs(Instant lastCandleTs) { this.lastCandleTs = lastCandleTs; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getActiveProfile() { return activeProfile; }
    public void setActiveProfile(String activeProfile) { this.activeProfile = activeProfile; }

    public Boolean getAutoExecutionEnabled() { return autoExecutionEnabled; }
    public void setAutoExecutionEnabled(Boolean autoExecutionEnabled) { this.autoExecutionEnabled = autoExecutionEnabled; }

    public BigDecimal getEntryAtr() { return entryAtr; }
    public void setEntryAtr(BigDecimal entryAtr) { this.entryAtr = entryAtr; }

    public BigDecimal getBestFavorablePrice() { return bestFavorablePrice; }
    public void setBestFavorablePrice(BigDecimal bestFavorablePrice) { this.bestFavorablePrice = bestFavorablePrice; }

    public BigDecimal getTrailingStopPrice() { return trailingStopPrice; }
    public void setTrailingStopPrice(BigDecimal trailingStopPrice) { this.trailingStopPrice = trailingStopPrice; }
}
