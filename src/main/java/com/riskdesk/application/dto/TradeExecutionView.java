package com.riskdesk.application.dto;

import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;

import java.math.BigDecimal;

public record TradeExecutionView(
    Long id,
    Long version,
    String executionKey,
    Long mentorSignalReviewId,
    String reviewAlertKey,
    Integer reviewRevision,
    String brokerAccountId,
    String instrument,
    String timeframe,
    String action,
    Integer quantity,
    ExecutionTriggerSource triggerSource,
    String requestedBy,
    ExecutionStatus status,
    String statusReason,
    BigDecimal normalizedEntryPrice,
    BigDecimal virtualStopLoss,
    BigDecimal virtualTakeProfit,
    BigDecimal disasterStopPrice,
    Long entryOrderId,
    Long disasterStopOrderId,
    BigDecimal lastReliableLivePrice,
    String lastReliableLivePriceAt,
    String createdAt,
    String updatedAt,
    String entrySubmittedAt,
    String entryFilledAt,
    String virtualExitTriggeredAt,
    String exitSubmittedAt,
    String closedAt
) {

    public static TradeExecutionView from(TradeExecutionRecord record) {
        return new TradeExecutionView(
            record.getId(),
            record.getVersion(),
            record.getExecutionKey(),
            record.getMentorSignalReviewId(),
            record.getReviewAlertKey(),
            record.getReviewRevision(),
            record.getBrokerAccountId(),
            record.getInstrument(),
            record.getTimeframe(),
            record.getAction(),
            record.getQuantity(),
            record.getTriggerSource(),
            record.getRequestedBy(),
            record.getStatus(),
            record.getStatusReason(),
            record.getNormalizedEntryPrice(),
            record.getVirtualStopLoss(),
            record.getVirtualTakeProfit(),
            record.getDisasterStopPrice(),
            record.getEntryOrderId(),
            record.getDisasterStopOrderId(),
            record.getLastReliableLivePrice(),
            toStringValue(record.getLastReliableLivePriceAt()),
            toStringValue(record.getCreatedAt()),
            toStringValue(record.getUpdatedAt()),
            toStringValue(record.getEntrySubmittedAt()),
            toStringValue(record.getEntryFilledAt()),
            toStringValue(record.getVirtualExitTriggeredAt()),
            toStringValue(record.getExitSubmittedAt()),
            toStringValue(record.getClosedAt())
        );
    }

    private static String toStringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
