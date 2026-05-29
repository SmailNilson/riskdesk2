package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "wtxrsi_strategy_states")
@IdClass(WtxRsiStrategyStateId.class)
public class WtxRsiStrategyStateEntity {

    @Id
    @Column(nullable = false, length = 20)
    private String instrument;

    @Id
    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(nullable = false, length = 10)
    private String currentDirection;  // FLAT, LONG, SHORT

    @Column(precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal entryQty;

    @Column(precision = 20, scale = 8)
    private BigDecimal stopLoss;

    @Column(precision = 20, scale = 8)
    private BigDecimal takeProfit;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal cumulativeRealizedPnl;

    @Column
    private Instant lastCandleTs;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Nullable for the same reason as WtxStrategyStateEntity#autoExecutionEnabled —
     *  Hibernate {@code ddl-auto=update} cannot add NOT NULL booleans on populated tables. */
    @Column
    private Boolean autoExecutionEnabled;

    @Column
    private Integer configuredOrderQty;

    /** Same ddl-auto=update reason: nullable so adding the column doesn't fail.
     *  Adapter coerces null to false. */
    @Column
    private Boolean swingBiasFilterEnabled;

    /** Snapshot of the most recently resolved bias for UI/diagnostics.
     *  Stored as enum name (BULLISH | BEARISH | NEUTRAL). */
    @Column(length = 10)
    private String lastSwingBias;

    /** Entry-only Chaikin gate per (instrument, timeframe). Nullable for the same
     *  ddl-auto=update reason as the other boolean toggles — existing rows get NULL
     *  when the column is added; the adapter coerces null to the global config
     *  default ({@code riskdesk.wtxrsi.chaikin-required}). */
    @Column
    private Boolean chaikinRequired;

    public WtxRsiStrategyStateEntity() {}

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public String getCurrentDirection() { return currentDirection; }
    public void setCurrentDirection(String currentDirection) { this.currentDirection = currentDirection; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public BigDecimal getEntryQty() { return entryQty; }
    public void setEntryQty(BigDecimal entryQty) { this.entryQty = entryQty; }
    public BigDecimal getStopLoss() { return stopLoss; }
    public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }
    public BigDecimal getTakeProfit() { return takeProfit; }
    public void setTakeProfit(BigDecimal takeProfit) { this.takeProfit = takeProfit; }
    public BigDecimal getCumulativeRealizedPnl() { return cumulativeRealizedPnl; }
    public void setCumulativeRealizedPnl(BigDecimal cumulativeRealizedPnl) { this.cumulativeRealizedPnl = cumulativeRealizedPnl; }
    public Instant getLastCandleTs() { return lastCandleTs; }
    public void setLastCandleTs(Instant lastCandleTs) { this.lastCandleTs = lastCandleTs; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Boolean getAutoExecutionEnabled() { return autoExecutionEnabled; }
    public void setAutoExecutionEnabled(Boolean autoExecutionEnabled) { this.autoExecutionEnabled = autoExecutionEnabled; }
    public Integer getConfiguredOrderQty() { return configuredOrderQty; }
    public void setConfiguredOrderQty(Integer configuredOrderQty) { this.configuredOrderQty = configuredOrderQty; }
    public Boolean getSwingBiasFilterEnabled() { return swingBiasFilterEnabled; }
    public void setSwingBiasFilterEnabled(Boolean swingBiasFilterEnabled) { this.swingBiasFilterEnabled = swingBiasFilterEnabled; }
    public String getLastSwingBias() { return lastSwingBias; }
    public void setLastSwingBias(String lastSwingBias) { this.lastSwingBias = lastSwingBias; }
    public Boolean getChaikinRequired() { return chaikinRequired; }
    public void setChaikinRequired(Boolean chaikinRequired) { this.chaikinRequired = chaikinRequired; }
}
