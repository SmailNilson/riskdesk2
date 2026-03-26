package com.riskdesk.infrastructure.persistence.entity;

import com.riskdesk.domain.model.TradeSimulationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "mentor_audits")
public class MentorAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128, unique = true)
    private String sourceRef;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 32)
    private String instrument;

    @Column(length = 16)
    private String timeframe;

    @Column(length = 16)
    private String action;

    @Column(length = 128)
    private String model;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String responseJson;

    @Column(length = 128)
    private String verdict;

    @Column(nullable = false)
    private boolean success;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String semanticText;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TradeSimulationStatus simulationStatus;

    private Instant activationTime;
    private Instant resolutionTime;

    @Column(precision = 18, scale = 6)
    private BigDecimal maxDrawdownPoints;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getSemanticText() { return semanticText; }
    public void setSemanticText(String semanticText) { this.semanticText = semanticText; }
    public TradeSimulationStatus getSimulationStatus() { return simulationStatus; }
    public void setSimulationStatus(TradeSimulationStatus simulationStatus) { this.simulationStatus = simulationStatus; }
    public Instant getActivationTime() { return activationTime; }
    public void setActivationTime(Instant activationTime) { this.activationTime = activationTime; }
    public Instant getResolutionTime() { return resolutionTime; }
    public void setResolutionTime(Instant resolutionTime) { this.resolutionTime = resolutionTime; }
    public BigDecimal getMaxDrawdownPoints() { return maxDrawdownPoints; }
    public void setMaxDrawdownPoints(BigDecimal maxDrawdownPoints) { this.maxDrawdownPoints = maxDrawdownPoints; }
}
