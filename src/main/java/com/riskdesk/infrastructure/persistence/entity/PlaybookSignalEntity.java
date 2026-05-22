package com.riskdesk.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "playbook_signals")
public class PlaybookSignalEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String instrument;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(nullable = false)
    private Instant evaluatedAt;

    @Column(nullable = false, length = 10)
    private String direction; // LONG, SHORT

    @Column(nullable = false)
    private int checklistScore;

    @Column(nullable = false, length = 50)
    private String setupType;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal entryPrice;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal stopLoss;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal takeProfit1;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal takeProfit2;

    @Column(length = 50)
    private String routingOutcome;

    @Column(length = 300)
    private String routingErrorMessage;

    public PlaybookSignalEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public Instant getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Instant evaluatedAt) { this.evaluatedAt = evaluatedAt; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public int getChecklistScore() { return checklistScore; }
    public void setChecklistScore(int checklistScore) { this.checklistScore = checklistScore; }

    public String getSetupType() { return setupType; }
    public void setSetupType(String setupType) { this.setupType = setupType; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getStopLoss() { return stopLoss; }
    public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }

    public BigDecimal getTakeProfit1() { return takeProfit1; }
    public void setTakeProfit1(BigDecimal takeProfit1) { this.takeProfit1 = takeProfit1; }

    public BigDecimal getTakeProfit2() { return takeProfit2; }
    public void setTakeProfit2(BigDecimal takeProfit2) { this.takeProfit2 = takeProfit2; }

    public String getRoutingOutcome() { return routingOutcome; }
    public void setRoutingOutcome(String routingOutcome) { this.routingOutcome = routingOutcome; }

    public String getRoutingErrorMessage() { return routingErrorMessage; }
    public void setRoutingErrorMessage(String routingErrorMessage) { this.routingErrorMessage = routingErrorMessage; }
}
