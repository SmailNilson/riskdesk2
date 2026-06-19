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
    /** Broker entry order type: {@code LIMIT} (default / null) | {@code STOP} | {@code STOP_LIMIT}. */
    private String orderType;
    /** Stop trigger for STOP / STOP_LIMIT entries (breakout arm price); null for LIMIT / MARKET. */
    private BigDecimal triggerPrice;
    /**
     * Contracts of an IN-FLIGHT partial close (scale-out / REDUCE). Set while a reducing leg rests
     * ({@code EXIT_SUBMITTED}); when that leg fills the row's {@code quantity} is decremented by this and
     * the row returns to ACTIVE (the fill tracker / router clear it). Null for a full close or no reduce.
     */
    private Integer closingQuantity;
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
    /**
     * IBKR {@code permId} — the DURABLE, never-reused broker order id (unlike {@code ibkrOrderId}, which
     * is session-scoped and reused after a gateway reconnect). The authoritative reconciliation key.
     */
    private Long permId;
    private String lastExecId;
    /**
     * Slice D — D2 (reverse deferred-open). When non-null on a {@code PENDING_ENTRY_SUBMISSION} row, this
     * row is the OPEN leg of a REVERSE held back behind the close leg's FILL: it is the PRIMARY KEY of the
     * close row it waits on. {@code ReverseDeferredOpenScheduler} reads that close row's status —
     * {@code CLOSED} (filled → submit the open) / {@code ACTIVE} (close cancelled without a fill, revived by
     * the fill tracker → cancel the deferred open) / {@code EXIT_SUBMITTED} (still resting → wait). Keyed by
     * the row PK, not the close order id, because the fill tracker DETACHES the order id when it revives a
     * cancelled close. Null on every other row.
     */
    private Long deferredReverseCloseRowId;

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

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public BigDecimal getTriggerPrice() {
        return triggerPrice;
    }

    public void setTriggerPrice(BigDecimal triggerPrice) {
        this.triggerPrice = triggerPrice;
    }

    public Integer getClosingQuantity() {
        return closingQuantity;
    }

    public void setClosingQuantity(Integer closingQuantity) {
        this.closingQuantity = closingQuantity;
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

    public Long getPermId() {
        return permId;
    }

    public void setPermId(Long permId) {
        this.permId = permId;
    }

    public String getLastExecId() {
        return lastExecId;
    }

    public void setLastExecId(String lastExecId) {
        this.lastExecId = lastExecId;
    }

    public Long getDeferredReverseCloseRowId() {
        return deferredReverseCloseRowId;
    }

    public void setDeferredReverseCloseRowId(Long deferredReverseCloseRowId) {
        this.deferredReverseCloseRowId = deferredReverseCloseRowId;
    }
}
