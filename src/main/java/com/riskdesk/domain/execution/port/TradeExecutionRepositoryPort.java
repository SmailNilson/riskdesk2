package com.riskdesk.domain.execution.port;

import com.riskdesk.domain.model.TradeExecutionRecord;

import java.util.Optional;

public interface TradeExecutionRepositoryPort {

    TradeExecutionRecord createIfAbsent(TradeExecutionRecord execution);

    TradeExecutionRecord save(TradeExecutionRecord execution);

    Optional<TradeExecutionRecord> findById(Long id);

    Optional<TradeExecutionRecord> findByMentorSignalReviewId(Long mentorSignalReviewId);
}
