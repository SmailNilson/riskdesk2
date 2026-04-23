package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.persistence.entity.TradeExecutionEntity;

final class TradeExecutionEntityMapper {

    private TradeExecutionEntityMapper() {
    }

    static TradeExecutionEntity toEntity(TradeExecutionRecord execution) {
        TradeExecutionEntity entity = new TradeExecutionEntity();
        entity.setId(execution.getId());
        entity.setVersion(execution.getVersion());
        entity.setExecutionKey(execution.getExecutionKey());
        entity.setMentorSignalReviewId(execution.getMentorSignalReviewId());
        entity.setReviewAlertKey(execution.getReviewAlertKey());
        entity.setReviewRevision(execution.getReviewRevision());
        entity.setBrokerAccountId(execution.getBrokerAccountId());
        entity.setInstrument(execution.getInstrument());
        entity.setTimeframe(execution.getTimeframe());
        entity.setAction(execution.getAction());
        entity.setQuantity(execution.getQuantity());
        entity.setTriggerSource(execution.getTriggerSource());
        entity.setRequestedBy(execution.getRequestedBy());
        entity.setStatus(execution.getStatus());
        entity.setStatusReason(execution.getStatusReason());
        entity.setNormalizedEntryPrice(execution.getNormalizedEntryPrice());
        entity.setVirtualStopLoss(execution.getVirtualStopLoss());
        entity.setVirtualTakeProfit(execution.getVirtualTakeProfit());
        entity.setDisasterStopPrice(execution.getDisasterStopPrice());
        entity.setEntryOrderId(execution.getEntryOrderId());
        entity.setDisasterStopOrderId(execution.getDisasterStopOrderId());
        entity.setLastReliableLivePrice(execution.getLastReliableLivePrice());
        entity.setLastReliableLivePriceAt(execution.getLastReliableLivePriceAt());
        entity.setCreatedAt(execution.getCreatedAt());
        entity.setUpdatedAt(execution.getUpdatedAt());
        entity.setEntrySubmittedAt(execution.getEntrySubmittedAt());
        entity.setEntryFilledAt(execution.getEntryFilledAt());
        entity.setVirtualExitTriggeredAt(execution.getVirtualExitTriggeredAt());
        entity.setExitSubmittedAt(execution.getExitSubmittedAt());
        entity.setClosedAt(execution.getClosedAt());
        entity.setFilledQuantity(execution.getFilledQuantity());
        entity.setAvgFillPrice(execution.getAvgFillPrice());
        entity.setLastFillTime(execution.getLastFillTime());
        entity.setOrderStatus(execution.getOrderStatus());
        entity.setIbkrOrderId(execution.getIbkrOrderId());
        entity.setLastExecId(execution.getLastExecId());
        return entity;
    }

    static TradeExecutionRecord toDomain(TradeExecutionEntity entity) {
        TradeExecutionRecord execution = new TradeExecutionRecord();
        execution.setId(entity.getId());
        execution.setVersion(entity.getVersion());
        execution.setExecutionKey(entity.getExecutionKey());
        execution.setMentorSignalReviewId(entity.getMentorSignalReviewId());
        execution.setReviewAlertKey(entity.getReviewAlertKey());
        execution.setReviewRevision(entity.getReviewRevision());
        execution.setBrokerAccountId(entity.getBrokerAccountId());
        execution.setInstrument(entity.getInstrument());
        execution.setTimeframe(entity.getTimeframe());
        execution.setAction(entity.getAction());
        execution.setQuantity(entity.getQuantity());
        execution.setTriggerSource(entity.getTriggerSource());
        execution.setRequestedBy(entity.getRequestedBy());
        execution.setStatus(entity.getStatus());
        execution.setStatusReason(entity.getStatusReason());
        execution.setNormalizedEntryPrice(entity.getNormalizedEntryPrice());
        execution.setVirtualStopLoss(entity.getVirtualStopLoss());
        execution.setVirtualTakeProfit(entity.getVirtualTakeProfit());
        execution.setDisasterStopPrice(entity.getDisasterStopPrice());
        execution.setEntryOrderId(entity.getEntryOrderId());
        execution.setDisasterStopOrderId(entity.getDisasterStopOrderId());
        execution.setLastReliableLivePrice(entity.getLastReliableLivePrice());
        execution.setLastReliableLivePriceAt(entity.getLastReliableLivePriceAt());
        execution.setCreatedAt(entity.getCreatedAt());
        execution.setUpdatedAt(entity.getUpdatedAt());
        execution.setEntrySubmittedAt(entity.getEntrySubmittedAt());
        execution.setEntryFilledAt(entity.getEntryFilledAt());
        execution.setVirtualExitTriggeredAt(entity.getVirtualExitTriggeredAt());
        execution.setExitSubmittedAt(entity.getExitSubmittedAt());
        execution.setClosedAt(entity.getClosedAt());
        execution.setFilledQuantity(entity.getFilledQuantity());
        execution.setAvgFillPrice(entity.getAvgFillPrice());
        execution.setLastFillTime(entity.getLastFillTime());
        execution.setOrderStatus(entity.getOrderStatus());
        execution.setIbkrOrderId(entity.getIbkrOrderId());
        execution.setLastExecId(entity.getLastExecId());
        return execution;
    }
}
