package com.riskdesk.domain.model;

import java.time.Instant;

public class MentorSignalReviewRecord {

    private Long id;
    private String alertKey;
    private int revision;
    private String triggerType;
    private String status;
    private String severity;
    private String category;
    private String message;
    private String instrument;
    private String timeframe;
    private String action;
    private Instant alertTimestamp;
    private Instant createdAt;
    private String selectedTimezone;
    private Instant completedAt;
    private String snapshotJson;
    private String analysisJson;
    private String verdict;
    private String errorMessage;
    private ExecutionEligibilityStatus executionEligibilityStatus;
    private String executionEligibilityReason;
    private TradeSimulationStatus simulationStatus;
    private Instant activationTime;
    private Instant resolutionTime;
    private java.math.BigDecimal maxDrawdownPoints;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAlertKey() {
        return alertKey;
    }

    public void setAlertKey(String alertKey) {
        this.alertKey = alertKey;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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

    public Instant getAlertTimestamp() {
        return alertTimestamp;
    }

    public void setAlertTimestamp(Instant alertTimestamp) {
        this.alertTimestamp = alertTimestamp;
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    public String getAnalysisJson() {
        return analysisJson;
    }

    public void setAnalysisJson(String analysisJson) {
        this.analysisJson = analysisJson;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public ExecutionEligibilityStatus getExecutionEligibilityStatus() {
        return executionEligibilityStatus;
    }

    public void setExecutionEligibilityStatus(ExecutionEligibilityStatus executionEligibilityStatus) {
        this.executionEligibilityStatus = executionEligibilityStatus;
    }

    public String getExecutionEligibilityReason() {
        return executionEligibilityReason;
    }

    public void setExecutionEligibilityReason(String executionEligibilityReason) {
        this.executionEligibilityReason = executionEligibilityReason;
    }

    public TradeSimulationStatus getSimulationStatus() {
        return simulationStatus;
    }

    public void setSimulationStatus(TradeSimulationStatus simulationStatus) {
        this.simulationStatus = simulationStatus;
    }

    public Instant getActivationTime() {
        return activationTime;
    }

    public void setActivationTime(Instant activationTime) {
        this.activationTime = activationTime;
    }

    public Instant getResolutionTime() {
        return resolutionTime;
    }

    public void setResolutionTime(Instant resolutionTime) {
        this.resolutionTime = resolutionTime;
    }

    public java.math.BigDecimal getMaxDrawdownPoints() {
        return maxDrawdownPoints;
    }

    public void setMaxDrawdownPoints(java.math.BigDecimal maxDrawdownPoints) {
        this.maxDrawdownPoints = maxDrawdownPoints;
    }
}
