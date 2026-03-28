package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaTradeExecutionRepositoryAdapter implements TradeExecutionRepositoryPort {

    private final TradeExecutionJpaRepository repository;

    public JpaTradeExecutionRepositoryAdapter(TradeExecutionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public TradeExecutionRecord createIfAbsent(TradeExecutionRecord execution) {
        try {
            return TradeExecutionEntityMapper.toDomain(
                repository.saveAndFlush(TradeExecutionEntityMapper.toEntity(execution))
            );
        } catch (DataIntegrityViolationException e) {
            return repository.findByMentorSignalReviewId(execution.getMentorSignalReviewId())
                .map(TradeExecutionEntityMapper::toDomain)
                .orElseThrow(() -> e);
        }
    }

    @Override
    public TradeExecutionRecord save(TradeExecutionRecord execution) {
        try {
            return TradeExecutionEntityMapper.toDomain(
                repository.saveAndFlush(TradeExecutionEntityMapper.toEntity(execution))
            );
        } catch (ObjectOptimisticLockingFailureException e) {
            throw e;
        }
    }

    @Override
    public Optional<TradeExecutionRecord> findById(Long id) {
        return repository.findById(id).map(TradeExecutionEntityMapper::toDomain);
    }

    @Override
    public Optional<TradeExecutionRecord> findByMentorSignalReviewId(Long mentorSignalReviewId) {
        return repository.findByMentorSignalReviewId(mentorSignalReviewId).map(TradeExecutionEntityMapper::toDomain);
    }
}
