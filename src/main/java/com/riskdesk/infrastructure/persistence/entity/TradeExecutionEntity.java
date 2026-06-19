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
        @Index(name = "idx_trade_executions_created_at", columnList = "createdAt"),
        @Index(name = "idx_trade_executions_ibkr_order_id", columnList = "ibkrOrderId"),
        @Index(name = "idx_trade_executions_perm_id", columnList = "permId")
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

    /**
     * Nullable since PR #303 — auto-armed quant executions are NOT tied to a
     * mentor review. The unique index {@code uk_trade_executions_review_id}
     * still guarantees one execution per review when present (Postgres treats
     * NULLs as distinct in unique indexes, so multiple auto-arm rows with NULL
     * coexist freely).
     */
    @Column(nullable = true)
    private Long mentorSignalReviewId;

    /** Nullable since PR #303 — see {@link #mentorSignalReviewId}. */
    @Column(nullable = true, length = 512)
    private String reviewAlertKey;

    /** Nullable since PR #303 — see {@link #mentorSignalReviewId}. */
    @Column(nullable = true)
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

    /** Broker entry order type: LIMIT (default / null) | STOP | STOP_LIMIT. Nullable = LIMIT for legacy rows. */
    @Column(length = 16)
    private String orderType;

    /** Stop trigger for STOP / STOP_LIMIT entries (breakout arm price); null for LIMIT / MARKET. */
    @Column(precision = 19, scale = 6)
    private BigDecimal triggerPrice;

    /** Contracts of an in-flight partial close (REDUCE) resting at the broker; null otherwise. */
    @Column
    private Integer closingQuantity;

    /**
     * Nullable since WTX_AUTO / QUANT_AUTO_ARM rows are armed before any stop
     * loss is known (BASELINE has no SL; trailing arms only after activation).
     * The mentor execution path still sets it from the review's planned stop.
     */
    @Column(nullable = true, precision = 19, scale = 6)
    private BigDecimal virtualStopLoss;

    /** Nullable since WTX/Quant auto rows have no pre-armed take profit — see {@link #virtualStopLoss}. */
    @Column(nullable = true, precision = 19, scale = 6)
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

    /**
     * Slice 3a — IBKR execDetails/orderStatus fill tracking.
     * These raw IBKR feedback fields live alongside (not in place of) the domain
     * execution lifecycle status enum. They record the broker's view of the order:
     *   - filledQuantity     : cumulative quantity filled (BigDecimal, IBKR Decimal mirror)
     *   - avgFillPrice       : volume-weighted average fill price
     *   - lastFillTime       : UTC timestamp of the last processed exec report
     *   - orderStatus        : IBKR status name (Submitted, PreSubmitted, PartiallyFilled, Filled, Cancelled, …)
     *   - ibkrOrderId        : TWS orderId (used for startup reconciliation in 3b)
     *   - lastExecId         : per-fill idempotence key (IBKR execId of the last applied exec report)
     */
    @Column(precision = 19, scale = 6)
    private BigDecimal filledQuantity;

    @Column(precision = 19, scale = 6)
    private BigDecimal avgFillPrice;

    @Column
    private Instant lastFillTime;

    @Column(length = 32)
    private String orderStatus;

    @Column
    private Integer ibkrOrderId;

    /** IBKR permId — durable, never reused (unlike ibkrOrderId). Authoritative reconciliation key.
     *  Nullable → ddl-auto adds it clean. */
    @Column
    private Long permId;

    @Column(length = 64)
    private String lastExecId;

    /** Slice D — D2: PK of the resting close row a deferred REVERSE open leg waits on (non-null only on a
     *  PENDING_ENTRY_SUBMISSION deferred-open row). Nullable → ddl-auto adds it clean. */
    @Column
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

    public Long getDeferredReverseCloseRowId() {
        return deferredReverseCloseRowId;
    }

    public void setDeferredReverseCloseRowId(Long deferredReverseCloseRowId) {
        this.deferredReverseCloseRowId = deferredReverseCloseRowId;
    }

    public void setLastExecId(String lastExecId) {
        this.lastExecId = lastExecId;
    }
}
