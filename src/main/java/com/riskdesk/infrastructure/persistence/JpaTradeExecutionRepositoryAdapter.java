package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.persistence.entity.TradeExecutionEntity;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
public class JpaTradeExecutionRepositoryAdapter implements TradeExecutionRepositoryPort {

    private final TradeExecutionJpaRepository repository;
    private final EntityManager entityManager;

    public JpaTradeExecutionRepositoryAdapter(TradeExecutionJpaRepository repository,
                                              EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public TradeExecutionRecord createIfAbsent(TradeExecutionRecord execution) {
        Optional<TradeExecutionEntity> existing = repository.findByMentorSignalReviewId(execution.getMentorSignalReviewId());
        if (existing.isPresent()) {
            return TradeExecutionEntityMapper.toDomain(existing.get());
        }

        try {
            return TradeExecutionEntityMapper.toDomain(
                repository.saveAndFlush(TradeExecutionEntityMapper.toEntity(execution))
            );
        } catch (DataIntegrityViolationException e) {
            entityManager.clear();
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
    public Optional<TradeExecutionRecord> findByIdForUpdate(Long id) {
        return repository.findByIdForUpdate(id).map(TradeExecutionEntityMapper::toDomain);
    }

    @Override
    public Optional<TradeExecutionRecord> findByMentorSignalReviewId(Long mentorSignalReviewId) {
        return repository.findByMentorSignalReviewId(mentorSignalReviewId).map(TradeExecutionEntityMapper::toDomain);
    }

    @Override
    public List<TradeExecutionRecord> findByMentorSignalReviewIds(Collection<Long> mentorSignalReviewIds) {
        if (mentorSignalReviewIds == null || mentorSignalReviewIds.isEmpty()) {
            return List.of();
        }
        return repository.findAllByMentorSignalReviewIdIn(mentorSignalReviewIds).stream()
            .map(TradeExecutionEntityMapper::toDomain)
            .toList();
    }
}
