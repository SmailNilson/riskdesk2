package com.riskdesk.domain.externalsetup;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Externally-submitted trade setup awaiting (or completed) human validation.
 *
 * <p>Plain domain POJO — no JPA, no Spring annotations. Persistence is delegated to
 * {@link com.riskdesk.domain.externalsetup.port.ExternalSetupRepositoryPort}.</p>
 *
 * <p>Lifecycle is tracked via {@link ExternalSetupStatus}. The setup carries enough context
 * (entry / sl / tp / triggerLabel / payloadJson) to be re-displayed in the UI without any
 * additional lookup.</p>
 *
 * <p>The validation contract is: when the user clicks ✅ Validate, execution uses the current
 * live price (configurable per-call via {@code overrideEntryPrice}) — not the original entry —
 * to avoid stale fills. The original {@code entry} is preserved for audit / R:R rendering.</p>
 */
public class ExternalSetup {

    private Long id;
    private String setupKey;
    private Instrument instrument;
    private Side direction;
    private BigDecimal entry;
    private BigDecimal stopLoss;
    private BigDecimal takeProfit1;
    private BigDecimal takeProfit2;
    private ExternalSetupConfidence confidence;
    private String triggerLabel;
    private String payloadJson;
    private ExternalSetupSource source;
    private String sourceRef;
    private ExternalSetupStatus status;
    private Instant submittedAt;
    private Instant expiresAt;
    private Instant validatedAt;
    private String validatedBy;
    private String rejectionReason;
    private Long tradeExecutionId;
    private BigDecimal executedAtPrice;
    private boolean autoExecuted;

    public ExternalSetup() {
    }

    /** Convenience for unit tests / non-persisted construction. */
    public static ExternalSetup forSubmission(
        String setupKey,
        Instrument instrument,
        Side direction,
        BigDecimal entry,
        BigDecimal stopLoss,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        ExternalSetupConfidence confidence,
        String triggerLabel,
        String payloadJson,
        ExternalSetupSource source,
        String sourceRef,
        Instant submittedAt,
        Instant expiresAt
    ) {
        ExternalSetup s = new ExternalSetup();
        s.setupKey = Objects.requireNonNull(setupKey);
        s.instrument = Objects.requireNonNull(instrument);
        s.direction = Objects.requireNonNull(direction);
        s.entry = Objects.requireNonNull(entry);
        s.stopLoss = stopLoss;
        s.takeProfit1 = takeProfit1;
        s.takeProfit2 = takeProfit2;
        s.confidence = Objects.requireNonNullElse(confidence, ExternalSetupConfidence.MEDIUM);
        s.triggerLabel = triggerLabel;
        s.payloadJson = payloadJson;
        s.source = Objects.requireNonNull(source);
        s.sourceRef = sourceRef;
        s.status = ExternalSetupStatus.PENDING;
        s.submittedAt = Objects.requireNonNull(submittedAt);
        s.expiresAt = Objects.requireNonNull(expiresAt);
        return s;
    }

    public boolean isPending() {
        return status == ExternalSetupStatus.PENDING;
    }

    public boolean isExpiredAt(Instant clock) {
        return expiresAt != null && !clock.isBefore(expiresAt);
    }

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
