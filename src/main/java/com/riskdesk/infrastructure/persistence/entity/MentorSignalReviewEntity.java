package com.riskdesk.infrastructure.persistence.entity;

import com.riskdesk.domain.model.ExecutionEligibilityStatus;
import com.riskdesk.domain.model.TradeSimulationStatus;
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

import java.time.Instant;
import java.math.BigDecimal;

@Entity
@Table(
    name = "mentor_signal_reviews",
    indexes = {
        @Index(name = "idx_mentor_signal_reviews_alert_key", columnList = "alertKey"),
        @Index(name = "idx_mentor_signal_reviews_created_at", columnList = "createdAt"),
        @Index(name = "idx_mentor_signal_reviews_source_type", columnList = "sourceType")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_mentor_signal_reviews_alert_revision", columnNames = {"alertKey", "revision"})
    }
)
public class MentorSignalReviewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String alertKey;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false, length = 32)
    private String triggerType;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(length = 16)
    private String instrument;

    @Column(length = 16)
    private String timeframe;

    @Column(length = 16)
    private String action;

    @Column(nullable = false)
    private Instant alertTimestamp;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 64)
    private String selectedTimezone;

    @Column
    private Instant completedAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Column(columnDefinition = "TEXT")
    private String analysisJson;

    @Column(length = 128)
    private String verdict;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ExecutionEligibilityStatus executionEligibilityStatus = ExecutionEligibilityStatus.NOT_EVALUATED;

    @Column(length = 256)
    private String executionEligibilityReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TradeSimulationStatus simulationStatus;

    @Column
    private Instant activationTime;

    @Column
    private Instant resolutionTime;

    @Column(precision = 19, scale = 6)
    private BigDecimal maxDrawdownPoints;

    @Column(length = 16, nullable = false)
    private String sourceType = "SIGNAL";

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

    public BigDecimal getMaxDrawdownPoints() {
        return maxDrawdownPoints;
    }

    public void setMaxDrawdownPoints(BigDecimal maxDrawdownPoints) {
        this.maxDrawdownPoints = maxDrawdownPoints;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }
}
