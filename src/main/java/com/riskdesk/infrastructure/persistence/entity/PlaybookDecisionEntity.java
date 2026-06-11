package com.riskdesk.infrastructure.persistence.entity;

import com.riskdesk.domain.playbook.automation.PlaybookRoutingOutcome;
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
    name = "playbook_decisions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_playbook_decisions_key", columnNames = "decision_key")
    },
    indexes = {
        @Index(name = "idx_playbook_decisions_panel_created",
               columnList = "instrument, timeframe, created_at"),
        @Index(name = "idx_playbook_decisions_score",
               columnList = "checklist_score")
    }
)
public class PlaybookDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "decision_key", nullable = false, length = 160)
    private String decisionKey;

    @Column(nullable = false, length = 20)
    private String instrument;

    @Column(nullable = false, length = 10)
    private String timeframe;

    @Column(name = "setup_identity", length = 160)
    private String setupIdentity;

    @Column(name = "setup_type", length = 40)
    private String setupType;

    @Column(name = "zone_name", length = 160)
    private String zoneName;

    @Column(nullable = false, length = 8)
    private String direction;

    @Column(name = "checklist_score", nullable = false)
    private int checklistScore;

    @Column(length = 256)
    private String verdict;

    @Column(name = "entry_price", precision = 19, scale = 6)
    private BigDecimal entryPrice;

    @Column(name = "stop_loss", precision = 19, scale = 6)
    private BigDecimal stopLoss;

    @Column(name = "take_profit1", precision = 19, scale = 6)
    private BigDecimal takeProfit1;

    @Column(name = "take_profit2", precision = 19, scale = 6)
    private BigDecimal takeProfit2;

    @Column(name = "rr_ratio", precision = 10, scale = 4)
    private BigDecimal rrRatio;

    @Column(name = "risk_percent", precision = 10, scale = 6)
    private BigDecimal riskPercent;

    @Column(nullable = false)
    private boolean lateEntry;

    @Column(name = "price_source", length = 32)
    private String priceSource;

    @Column(name = "price_timestamp", columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant priceTimestamp;

    @Column(name = "evaluated_candle_ts", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant evaluatedCandleTs;

    /** LIMIT (null = legacy limit semantics) or STOP for confirmation entries. */
    @Column(name = "entry_type", length = 12)
    private String entryType;

    /** Pre-fill invalidation level for STOP entries — pending setup cancels beyond it. */
    @Column(name = "invalidation_price", precision = 19, scale = 6)
    private BigDecimal invalidationPrice;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "routing_outcome", length = 48)
    private PlaybookRoutingOutcome routingOutcome;

    @Column(name = "routing_error_message", length = 256)
    private String routingErrorMessage;

    @Column(name = "execution_id")
    private Long executionId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDecisionKey() { return decisionKey; }
    public void setDecisionKey(String decisionKey) { this.decisionKey = decisionKey; }
    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public String getSetupIdentity() { return setupIdentity; }
    public void setSetupIdentity(String setupIdentity) { this.setupIdentity = setupIdentity; }
    public String getSetupType() { return setupType; }
    public void setSetupType(String setupType) { this.setupType = setupType; }
    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public int getChecklistScore() { return checklistScore; }
    public void setChecklistScore(int checklistScore) { this.checklistScore = checklistScore; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public BigDecimal getStopLoss() { return stopLoss; }
    public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }
    public BigDecimal getTakeProfit1() { return takeProfit1; }
    public void setTakeProfit1(BigDecimal takeProfit1) { this.takeProfit1 = takeProfit1; }
    public BigDecimal getTakeProfit2() { return takeProfit2; }
    public void setTakeProfit2(BigDecimal takeProfit2) { this.takeProfit2 = takeProfit2; }
    public BigDecimal getRrRatio() { return rrRatio; }
    public void setRrRatio(BigDecimal rrRatio) { this.rrRatio = rrRatio; }
    public BigDecimal getRiskPercent() { return riskPercent; }
    public void setRiskPercent(BigDecimal riskPercent) { this.riskPercent = riskPercent; }
    public boolean isLateEntry() { return lateEntry; }
    public void setLateEntry(boolean lateEntry) { this.lateEntry = lateEntry; }
    public String getPriceSource() { return priceSource; }
    public void setPriceSource(String priceSource) { this.priceSource = priceSource; }
    public Instant getPriceTimestamp() { return priceTimestamp; }
    public void setPriceTimestamp(Instant priceTimestamp) { this.priceTimestamp = priceTimestamp; }
    public Instant getEvaluatedCandleTs() { return evaluatedCandleTs; }
    public void setEvaluatedCandleTs(Instant evaluatedCandleTs) { this.evaluatedCandleTs = evaluatedCandleTs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public PlaybookRoutingOutcome getRoutingOutcome() { return routingOutcome; }
    public void setRoutingOutcome(PlaybookRoutingOutcome routingOutcome) { this.routingOutcome = routingOutcome; }
    public String getRoutingErrorMessage() { return routingErrorMessage; }
    public void setRoutingErrorMessage(String routingErrorMessage) { this.routingErrorMessage = routingErrorMessage; }
    public Long getExecutionId() { return executionId; }
    public void setExecutionId(Long executionId) { this.executionId = executionId; }

    public String getEntryType() {
        return entryType;
    }

    public void setEntryType(String entryType) {
        this.entryType = entryType;
    }

    public BigDecimal getInvalidationPrice() {
        return invalidationPrice;
    }

    public void setInvalidationPrice(BigDecimal invalidationPrice) {
        this.invalidationPrice = invalidationPrice;
    }
}
