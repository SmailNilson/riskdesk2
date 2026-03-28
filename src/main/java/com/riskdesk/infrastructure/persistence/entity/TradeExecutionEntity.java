package com.riskdesk.infrastructure.persistence.entity;

import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
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
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "trade_executions",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_trade_executions_review_id", columnNames = "mentorSignalReviewId"),
        @UniqueConstraint(name = "uk_trade_executions_execution_key", columnNames = "executionKey")
    },
    indexes = {
        @Index(name = "idx_trade_executions_status", columnList = "status"),
        @Index(name = "idx_trade_executions_review_alert_key", columnList = "reviewAlertKey"),
        @Index(name = "idx_trade_executions_created_at", columnList = "createdAt")
    }
)
public class TradeExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, length = 128)
    private String executionKey;

    @Column(nullable = false)
    private Long mentorSignalReviewId;

    @Column(nullable = false, length = 512)
    private String reviewAlertKey;

    @Column(nullable = false)
    private Integer reviewRevision;

    @Column(nullable = false, length = 64)
    private String brokerAccountId;

    @Column(nullable = false, length = 16)
    private String instrument;

    @Column(nullable = false, length = 16)
    private String timeframe;

    @Column(nullable = false, length = 16)
    private String action;

    @Column
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExecutionTriggerSource triggerSource;

    @Column(length = 128)
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExecutionStatus status;

    @Column(length = 256)
    private String statusReason;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal normalizedEntryPrice;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal virtualStopLoss;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal virtualTakeProfit;

    @Column(precision = 19, scale = 6)
    private BigDecimal disasterStopPrice;

    @Column
    private Long entryOrderId;

    @Column
    private Long disasterStopOrderId;

    @Column(precision = 19, scale = 6)
    private BigDecimal lastReliableLivePrice;

    @Column
    private Instant lastReliableLivePriceAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant entrySubmittedAt;

    @Column
    private Instant entryFilledAt;

    @Column
    private Instant virtualExitTriggeredAt;

    @Column
    private Instant exitSubmittedAt;

    @Column
    private Instant closedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getExecutionKey() {
        return executionKey;
    }

    public void setExecutionKey(String executionKey) {
        this.executionKey = executionKey;
    }

    public Long getMentorSignalReviewId() {
        return mentorSignalReviewId;
    }

    public void setMentorSignalReviewId(Long mentorSignalReviewId) {
        this.mentorSignalReviewId = mentorSignalReviewId;
    }

    public String getReviewAlertKey() {
        return reviewAlertKey;
    }

    public void setReviewAlertKey(String reviewAlertKey) {
        this.reviewAlertKey = reviewAlertKey;
    }

    public Integer getReviewRevision() {
        return reviewRevision;
    }

    public void setReviewRevision(Integer reviewRevision) {
        this.reviewRevision = reviewRevision;
    }

    public String getBrokerAccountId() {
        return brokerAccountId;
    }

    public void setBrokerAccountId(String brokerAccountId) {
        this.brokerAccountId = brokerAccountId;
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

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public ExecutionTriggerSource getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(ExecutionTriggerSource triggerSource) {
        this.triggerSource = triggerSource;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public BigDecimal getNormalizedEntryPrice() {
        return normalizedEntryPrice;
    }

    public void setNormalizedEntryPrice(BigDecimal normalizedEntryPrice) {
        this.normalizedEntryPrice = normalizedEntryPrice;
    }

    public BigDecimal getVirtualStopLoss() {
        return virtualStopLoss;
    }

    public void setVirtualStopLoss(BigDecimal virtualStopLoss) {
        this.virtualStopLoss = virtualStopLoss;
    }

    public BigDecimal getVirtualTakeProfit() {
        return virtualTakeProfit;
    }

    public void setVirtualTakeProfit(BigDecimal virtualTakeProfit) {
        this.virtualTakeProfit = virtualTakeProfit;
    }

    public BigDecimal getDisasterStopPrice() {
        return disasterStopPrice;
    }

    public void setDisasterStopPrice(BigDecimal disasterStopPrice) {
        this.disasterStopPrice = disasterStopPrice;
    }

    public Long getEntryOrderId() {
        return entryOrderId;
    }

    public void setEntryOrderId(Long entryOrderId) {
        this.entryOrderId = entryOrderId;
    }

    public Long getDisasterStopOrderId() {
        return disasterStopOrderId;
    }

    public void setDisasterStopOrderId(Long disasterStopOrderId) {
        this.disasterStopOrderId = disasterStopOrderId;
    }

    public BigDecimal getLastReliableLivePrice() {
        return lastReliableLivePrice;
    }

    public void setLastReliableLivePrice(BigDecimal lastReliableLivePrice) {
        this.lastReliableLivePrice = lastReliableLivePrice;
    }

    public Instant getLastReliableLivePriceAt() {
        return lastReliableLivePriceAt;
    }

    public void setLastReliableLivePriceAt(Instant lastReliableLivePriceAt) {
        this.lastReliableLivePriceAt = lastReliableLivePriceAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getEntrySubmittedAt() {
        return entrySubmittedAt;
    }

    public void setEntrySubmittedAt(Instant entrySubmittedAt) {
        this.entrySubmittedAt = entrySubmittedAt;
    }

    public Instant getEntryFilledAt() {
        return entryFilledAt;
    }

    public void setEntryFilledAt(Instant entryFilledAt) {
        this.entryFilledAt = entryFilledAt;
    }

    public Instant getVirtualExitTriggeredAt() {
        return virtualExitTriggeredAt;
    }

    public void setVirtualExitTriggeredAt(Instant virtualExitTriggeredAt) {
        this.virtualExitTriggeredAt = virtualExitTriggeredAt;
    }

    public Instant getExitSubmittedAt() {
        return exitSubmittedAt;
    }

    public void setExitSubmittedAt(Instant exitSubmittedAt) {
        this.exitSubmittedAt = exitSubmittedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }
}
