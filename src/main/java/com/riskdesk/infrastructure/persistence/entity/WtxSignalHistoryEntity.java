package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "wtx_signal_history",
    indexes = {
        @Index(name = "idx_wtx_signals_instrument_ts", columnList = "instrument, signalTs"),
        @Index(name = "idx_wtx_signals_created", columnList = "createdAt")
    }
)
public class WtxSignalHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String instrument;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(nullable = false, length = 20)
    private String signalType; // COMPRA, VENTA, COMPRA_1, VENTA_1

    @Column(nullable = false, length = 10)
    private String direction; // LONG, SHORT

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal wt1Value;

    @Column(nullable = false, precision = 20, scale = 6)
    private BigDecimal wt2Value;

    @Column(nullable = false)
    private boolean canTrade;

    @Column(nullable = false, length = 30)
    private String actionTaken;

    @Column(columnDefinition = "text")
    private String enrichmentJson;

    /** IBKR routing outcome; null when routing was never attempted (e.g. action NONE). */
    @Column(length = 30)
    private String routingOutcome;

    @Column(nullable = false)
    private Instant signalTs;

    @Column(nullable = false)
    private Instant createdAt;

    public WtxSignalHistoryEntity() {}

    public Long getId() { return id; }

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public String getSignalType() { return signalType; }
    public void setSignalType(String signalType) { this.signalType = signalType; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public BigDecimal getWt1Value() { return wt1Value; }
    public void setWt1Value(BigDecimal wt1Value) { this.wt1Value = wt1Value; }

    public BigDecimal getWt2Value() { return wt2Value; }
    public void setWt2Value(BigDecimal wt2Value) { this.wt2Value = wt2Value; }

    public boolean isCanTrade() { return canTrade; }
    public void setCanTrade(boolean canTrade) { this.canTrade = canTrade; }

    public String getActionTaken() { return actionTaken; }
    public void setActionTaken(String actionTaken) { this.actionTaken = actionTaken; }

    public String getEnrichmentJson() { return enrichmentJson; }
    public void setEnrichmentJson(String enrichmentJson) { this.enrichmentJson = enrichmentJson; }

    public String getRoutingOutcome() { return routingOutcome; }
    public void setRoutingOutcome(String routingOutcome) { this.routingOutcome = routingOutcome; }

    public Instant getSignalTs() { return signalTs; }
    public void setSignalTs(Instant signalTs) { this.signalTs = signalTs; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
