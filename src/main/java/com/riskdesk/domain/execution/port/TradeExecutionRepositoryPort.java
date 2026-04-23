package com.riskdesk.domain.execution.port;

import com.riskdesk.domain.model.TradeExecutionRecord;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeExecutionRepositoryPort {

    TradeExecutionRecord createIfAbsent(TradeExecutionRecord execution);

    TradeExecutionRecord save(TradeExecutionRecord execution);

    Optional<TradeExecutionRecord> findById(Long id);

    Optional<TradeExecutionRecord> findByIdForUpdate(Long id);

    Optional<TradeExecutionRecord> findByMentorSignalReviewId(Long mentorSignalReviewId);

    List<TradeExecutionRecord> findByMentorSignalReviewIds(Collection<Long> mentorSignalReviewIds);

    /**
     * Slice 3a — IBKR fill tracking.
     * Lookup executions by the TWS {@code orderId} assigned when the entry order was placed.
     */
    Optional<TradeExecutionRecord> findByIbkrOrderId(Integer ibkrOrderId);

    /**
     * Slice 3a — IBKR fill tracking.
     * Fallback lookup by {@code orderRef} (which equals the {@code executionKey}) when
     * the IBKR order id has not yet been persisted on the execution row.
     */
    Optional<TradeExecutionRecord> findByExecutionKey(String executionKey);
}
