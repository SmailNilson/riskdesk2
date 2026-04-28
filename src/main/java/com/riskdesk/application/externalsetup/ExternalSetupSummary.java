package com.riskdesk.application.externalsetup;

import com.riskdesk.domain.externalsetup.ExternalSetup;
import com.riskdesk.domain.externalsetup.ExternalSetupConfidence;
import com.riskdesk.domain.externalsetup.ExternalSetupSource;
import com.riskdesk.domain.externalsetup.ExternalSetupStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * REST + WebSocket projection of {@link ExternalSetup}. Excludes large {@code payloadJson};
 * use {@code GET /api/external-setups/{id}} for the full payload.
 */
public record ExternalSetupSummary(
    Long id,
    String setupKey,
    Instrument instrument,
    Side direction,
    BigDecimal entry,
    BigDecimal stopLoss,
    BigDecimal takeProfit1,
    BigDecimal takeProfit2,
    ExternalSetupConfidence confidence,
    String triggerLabel,
    ExternalSetupSource source,
    String sourceRef,
    ExternalSetupStatus status,
    Instant submittedAt,
    Instant expiresAt,
    Instant validatedAt,
    String validatedBy,
    String rejectionReason,
    Long tradeExecutionId,
    BigDecimal executedAtPrice,
    boolean autoExecuted
) {
    public static ExternalSetupSummary from(ExternalSetup s) {
        return new ExternalSetupSummary(
            s.getId(),
            s.getSetupKey(),
            s.getInstrument(),
            s.getDirection(),
            s.getEntry(),
            s.getStopLoss(),
            s.getTakeProfit1(),
            s.getTakeProfit2(),
            s.getConfidence(),
            s.getTriggerLabel(),
            s.getSource(),
            s.getSourceRef(),
            s.getStatus(),
            s.getSubmittedAt(),
            s.getExpiresAt(),
            s.getValidatedAt(),
            s.getValidatedBy(),
            s.getRejectionReason(),
            s.getTradeExecutionId(),
            s.getExecutedAtPrice(),
            s.isAutoExecuted()
        );
    }
}
