package com.riskdesk.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public class MentorAudit {

    private Long id;
    private String sourceRef;
    private Instant createdAt;
    private String selectedTimezone;
    private String instrument;
    private String timeframe;
    private String action;
    private String model;
    private String payloadJson;
    private String responseJson;
    private String verdict;
    private boolean success;
    private String errorMessage;
    private String semanticText;
    private TradeSimulationStatus simulationStatus;
    private Instant activationTime;
    private Instant resolutionTime;
    private BigDecimal maxDrawdownPoints;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getSelectedTimezone() {
        return selectedTimezone;
    }

    public void setSelectedTimezone(String selectedTimezone) {
        this.selectedTimezone = selectedTimezone;
    }

    public String getInstrument() {
        return instrument;
    }

    public void setInstrument(String instrument) {
        this.instrument = instrument;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getSemanticText() {
        return semanticText;
    }

    public void setSemanticText(String semanticText) {
        this.semanticText = semanticText;
    }

    public TradeSimulationStatus getSimulationStatus() { return simulationStatus; }
    public void setSimulationStatus(TradeSimulationStatus simulationStatus) { this.simulationStatus = simulationStatus; }
    public Instant getActivationTime() { return activationTime; }
    public void setActivationTime(Instant activationTime) { this.activationTime = activationTime; }
    public Instant getResolutionTime() { return resolutionTime; }
    public void setResolutionTime(Instant resolutionTime) { this.resolutionTime = resolutionTime; }
    public BigDecimal getMaxDrawdownPoints() { return maxDrawdownPoints; }
    public void setMaxDrawdownPoints(BigDecimal maxDrawdownPoints) { this.maxDrawdownPoints = maxDrawdownPoints; }
}
