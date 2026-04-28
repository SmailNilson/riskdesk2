package com.riskdesk.infrastructure.externalsetup;

import com.riskdesk.domain.externalsetup.ExternalSetupConfidence;
import com.riskdesk.domain.externalsetup.ExternalSetupSource;
import com.riskdesk.domain.externalsetup.ExternalSetupStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
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
    name = "external_setups",
    indexes = {
        @Index(name = "idx_external_setups_status_expires", columnList = "status, expiresAt"),
        @Index(name = "idx_external_setups_instrument_submitted", columnList = "instrument, submittedAt"),
        @Index(name = "idx_external_setups_source_ref", columnList = "sourceRef")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_external_setups_setup_key", columnNames = {"setupKey"})
    }
)
public class ExternalSetupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String setupKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Instrument instrument;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Side direction;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal entry;

    @Column(precision = 18, scale = 6)
    private BigDecimal stopLoss;

    @Column(precision = 18, scale = 6)
    private BigDecimal takeProfit1;

    @Column(precision = 18, scale = 6)
    private BigDecimal takeProfit2;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ExternalSetupConfidence confidence;

    @Column(length = 256)
    private String triggerLabel;

    /** Free-form snapshot from the detector (order flow + indicators). Up to ~64 KB realistic. */
    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExternalSetupSource source;

    @Column(length = 128)
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ExternalSetupStatus status;

    @Column(nullable = false)
    private Instant submittedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant validatedAt;

    @Column(length = 64)
    private String validatedBy;

    @Column(length = 512)
    private String rejectionReason;

    private Long tradeExecutionId;

    @Column(precision = 18, scale = 6)
    private BigDecimal executedAtPrice;

    @Column(nullable = false)
    private boolean autoExecuted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSetupKey() { return setupKey; }
    public void setSetupKey(String setupKey) { this.setupKey = setupKey; }
    public Instrument getInstrument() { return instrument; }
    public void setInstrument(Instrument instrument) { this.instrument = instrument; }
    public Side getDirection() { return direction; }
    public void setDirection(Side direction) { this.direction = direction; }
    public BigDecimal getEntry() { return entry; }
    public void setEntry(BigDecimal entry) { this.entry = entry; }
    public BigDecimal getStopLoss() { return stopLoss; }
    public void setStopLoss(BigDecimal stopLoss) { this.stopLoss = stopLoss; }
    public BigDecimal getTakeProfit1() { return takeProfit1; }
    public void setTakeProfit1(BigDecimal takeProfit1) { this.takeProfit1 = takeProfit1; }
    public BigDecimal getTakeProfit2() { return takeProfit2; }
    public void setTakeProfit2(BigDecimal takeProfit2) { this.takeProfit2 = takeProfit2; }
    public ExternalSetupConfidence getConfidence() { return confidence; }
    public void setConfidence(ExternalSetupConfidence confidence) { this.confidence = confidence; }
    public String getTriggerLabel() { return triggerLabel; }
    public void setTriggerLabel(String triggerLabel) { this.triggerLabel = triggerLabel; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public ExternalSetupSource getSource() { return source; }
    public void setSource(ExternalSetupSource source) { this.source = source; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public ExternalSetupStatus getStatus() { return status; }
    public void setStatus(ExternalSetupStatus status) { this.status = status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getValidatedAt() { return validatedAt; }
    public void setValidatedAt(Instant validatedAt) { this.validatedAt = validatedAt; }
    public String getValidatedBy() { return validatedBy; }
    public void setValidatedBy(String validatedBy) { this.validatedBy = validatedBy; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public Long getTradeExecutionId() { return tradeExecutionId; }
    public void setTradeExecutionId(Long tradeExecutionId) { this.tradeExecutionId = tradeExecutionId; }
    public BigDecimal getExecutedAtPrice() { return executedAtPrice; }
    public void setExecutedAtPrice(BigDecimal executedAtPrice) { this.executedAtPrice = executedAtPrice; }
    public boolean isAutoExecuted() { return autoExecuted; }
    public void setAutoExecuted(boolean autoExecuted) { this.autoExecuted = autoExecuted; }
}
