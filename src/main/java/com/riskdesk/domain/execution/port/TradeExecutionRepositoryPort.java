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
}
