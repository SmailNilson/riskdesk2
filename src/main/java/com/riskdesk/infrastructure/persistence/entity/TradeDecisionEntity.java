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

/**
 * JPA entity for a trade decision. Mirrors {@link com.riskdesk.domain.decision.model.TradeDecision}
 * with mutable fields for Hibernate. Conversion happens in {@link com.riskdesk.infrastructure.persistence.TradeDecisionEntityMapper}.
 *
 * <p>Indexes are sized for the two main access patterns: (a) UI shows recent decisions
 * newest-first, (b) revision thread for a given setup.
 */
@Entity
@Table(
    name = "trade_decisions",
    indexes = {
        @Index(name = "idx_trade_decisions_created_at", columnList = "createdAt"),
        @Index(name = "idx_trade_decisions_instrument_created", columnList = "instrument, createdAt"),
        @Index(name = "idx_trade_decisions_thread",
               columnList = "instrument, timeframe, direction, zoneName, revision")
    }
)
public class TradeDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 16)
    private String instrument;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Column(nullable = false, length = 8)
    private String direction;

    @Column(length = 32)
    private String setupType;

    @Column(length = 128)
    private String zoneName;

    @Column(nullable = false, length = 16)
    private String eligibility;

    @Column(nullable = false)
    private double sizePercent;

    @Column(length = 256)
    private String verdict;

    @Column(columnDefinition = "TEXT")
    private String agentVerdictsJson;

    @Column(columnDefinition = "TEXT")
    private String warningsJson;

    @Column(precision = 19, scale = 6)
    private BigDecimal entryPrice;

    @Column(precision = 19, scale = 6)
    private BigDecimal stopLoss;

    @Column(precision = 19, scale = 6)
    private BigDecimal takeProfit1;

    @Column(precision = 19, scale = 6)
    private BigDecimal takeProfit2;

    @Column
    private Double rrRatio;

    @Column(columnDefinition = "TEXT")
    private String narrative;

    @Column(length = 64)
    private String narrativeModel;

    @Column
    private Long narrativeLatencyMs;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getSetupType() { return setupType; }
    public void setSetupType(String setupType) { this.setupType = setupType; }

    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }

    public String getEligibility() { return eligibility; }
    public void setEligibility(String eligibility) { this.eligibility = eligibility; }

    public double getSizePercent() { return sizePercent; }
    public void setSizePercent(double sizePercent) { this.sizePercent = sizePercent; }

    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }

    public String getAgentVerdictsJson() { return agentVerdictsJson; }
    public void setAgentVerdictsJson(String agentVerdictsJson) { this.agentVerdictsJson = agentVerdictsJson; }

    public String getWarningsJson() { return warningsJson; }
    public void setWarningsJson(String warningsJson) { this.warningsJson = warningsJson; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getStopLoss() { return stopLoss; }
    public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }

    public BigDecimal getTakeProfit1() { return takeProfit1; }
    public void setTakeProfit1(BigDecimal takeProfit1) { this.takeProfit1 = takeProfit1; }

    public BigDecimal getTakeProfit2() { return takeProfit2; }
    public void setTakeProfit2(BigDecimal takeProfit2) { this.takeProfit2 = takeProfit2; }

    public Double getRrRatio() { return rrRatio; }
    public void setRrRatio(Double rrRatio) { this.rrRatio = rrRatio; }

    public String getNarrative() { return narrative; }
    public void setNarrative(String narrative) { this.narrative = narrative; }

    public String getNarrativeModel() { return narrativeModel; }
    public void setNarrativeModel(String narrativeModel) { this.narrativeModel = narrativeModel; }

    public Long getNarrativeLatencyMs() { return narrativeLatencyMs; }
    public void setNarrativeLatencyMs(Long narrativeLatencyMs) { this.narrativeLatencyMs = narrativeLatencyMs; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
