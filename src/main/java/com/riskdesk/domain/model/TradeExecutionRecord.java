package com.riskdesk.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public class TradeExecutionRecord {

    private Long id;
    private Long version;
    private String executionKey;
    private Long mentorSignalReviewId;
    private String reviewAlertKey;
    private Integer reviewRevision;
    private String brokerAccountId;
    private String instrument;
    private String timeframe;
    private String action;
    private Integer quantity;
    private ExecutionTriggerSource triggerSource;
    private String requestedBy;
    private ExecutionStatus status;
    private String statusReason;
    private BigDecimal normalizedEntryPrice;
    private BigDecimal virtualStopLoss;
    private BigDecimal virtualTakeProfit;
    private BigDecimal disasterStopPrice;
    private Long entryOrderId;
    private Long disasterStopOrderId;
    private BigDecimal lastReliableLivePrice;
    private Instant lastReliableLivePriceAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant entrySubmittedAt;
    private Instant entryFilledAt;
    private Instant virtualExitTriggeredAt;
    private Instant exitSubmittedAt;
    private Instant closedAt;

    // Slice 3a — IBKR fill tracking (raw broker feedback).
    private BigDecimal filledQuantity;
    private BigDecimal avgFillPrice;
    private Instant lastFillTime;
    private String orderStatus;
    private Integer ibkrOrderId;
    private String lastExecId;

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

    public BigDecimal getFilledQuantity() {
        return filledQuantity;
    }

    public void setFilledQuantity(BigDecimal filledQuantity) {
        this.filledQuantity = filledQuantity;
    }

    public BigDecimal getAvgFillPrice() {
        return avgFillPrice;
    }

    public void setAvgFillPrice(BigDecimal avgFillPrice) {
        this.avgFillPrice = avgFillPrice;
    }

    public Instant getLastFillTime() {
        return lastFillTime;
    }

    public void setLastFillTime(Instant lastFillTime) {
        this.lastFillTime = lastFillTime;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    public Integer getIbkrOrderId() {
        return ibkrOrderId;
    }

    public void setIbkrOrderId(Integer ibkrOrderId) {
        this.ibkrOrderId = ibkrOrderId;
    }

    public String getLastExecId() {
        return lastExecId;
    }

    public void setLastExecId(String lastExecId) {
        this.lastExecId = lastExecId;
    }
}
