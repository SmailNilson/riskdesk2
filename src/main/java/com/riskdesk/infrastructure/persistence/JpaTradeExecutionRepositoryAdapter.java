package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.persistence.entity.TradeExecutionEntity;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        return createIfAbsentTracked(execution).record();
    }

    @Override
    public CreateOutcome createIfAbsentTracked(TradeExecutionRecord execution) {
        // Auto-armed quant executions (PR #303) carry a NULL mentorSignalReviewId
        // — the de-dup key for those is executionKey only.
        if (execution.getMentorSignalReviewId() != null) {
            Optional<TradeExecutionEntity> existing = repository.findByMentorSignalReviewId(execution.getMentorSignalReviewId());
            if (existing.isPresent()) {
                return new CreateOutcome(TradeExecutionEntityMapper.toDomain(existing.get()), false);
            }
        } else if (execution.getExecutionKey() != null) {
            Optional<TradeExecutionEntity> existing = repository.findByExecutionKey(execution.getExecutionKey());
            if (existing.isPresent()) {
                return new CreateOutcome(TradeExecutionEntityMapper.toDomain(existing.get()), false);
            }
        }

        try {
            TradeExecutionRecord created = TradeExecutionEntityMapper.toDomain(
                repository.saveAndFlush(TradeExecutionEntityMapper.toEntity(execution))
            );
            return new CreateOutcome(created, true);
        } catch (DataIntegrityViolationException e) {
            // Lost the unique-constraint race: another caller created the row first.
            entityManager.clear();
            Optional<TradeExecutionEntity> existing = execution.getMentorSignalReviewId() != null
                ? repository.findByMentorSignalReviewId(execution.getMentorSignalReviewId())
                : repository.findByExecutionKey(execution.getExecutionKey());
            TradeExecutionRecord row = existing.map(TradeExecutionEntityMapper::toDomain).orElseThrow(() -> e);
            return new CreateOutcome(row, false);
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

    @Override
    public Optional<TradeExecutionRecord> findByIbkrOrderId(Integer ibkrOrderId) {
        if (ibkrOrderId == null) {
            return Optional.empty();
        }
        return repository.findByIbkrOrderId(ibkrOrderId).map(TradeExecutionEntityMapper::toDomain);
    }

    @Override
    public Optional<TradeExecutionRecord> findByExecutionKey(String executionKey) {
        if (executionKey == null || executionKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByExecutionKey(executionKey).map(TradeExecutionEntityMapper::toDomain);
    }

    /** Statuses that indicate an execution is no longer doing any work. Mirror
     *  of {@code AutoArmEvaluator.ACTIVE_STATUSES}'s complement — kept here so
     *  the JPA query works without coupling to the domain evaluator. */
    private static final Set<ExecutionStatus> TERMINAL_STATUSES = EnumSet.of(
        ExecutionStatus.CLOSED,
        ExecutionStatus.CANCELLED,
        ExecutionStatus.REJECTED,
        ExecutionStatus.FAILED
    );

    @Override
    public Optional<TradeExecutionRecord> findActiveByInstrument(String instrument) {
        if (instrument == null || instrument.isBlank()) {
            return Optional.empty();
        }
        return repository.findActiveByInstrumentRaw(instrument, TERMINAL_STATUSES).stream()
            .findFirst()
            .map(TradeExecutionEntityMapper::toDomain);
    }

    @Override
    public Optional<TradeExecutionRecord> findActiveByInstrumentAndTriggerSource(String instrument,
                                                                                 ExecutionTriggerSource triggerSource) {
        if (instrument == null || instrument.isBlank() || triggerSource == null) {
            return Optional.empty();
        }
        return repository.findActiveByInstrumentAndTriggerSourceRaw(instrument, triggerSource, TERMINAL_STATUSES).stream()
            .findFirst()
            .map(TradeExecutionEntityMapper::toDomain);
    }

    @Override
    public Optional<TradeExecutionRecord> findActiveByInstrumentAndTimeframeAndTriggerSource(
            String instrument, String timeframe, ExecutionTriggerSource triggerSource) {
        if (instrument == null || instrument.isBlank()
                || timeframe == null || timeframe.isBlank()
                || triggerSource == null) {
            return Optional.empty();
        }
        return repository.findActiveByInstrumentAndTimeframeAndTriggerSourceRaw(
                instrument, timeframe, triggerSource, TERMINAL_STATUSES).stream()
            .findFirst()
            .map(TradeExecutionEntityMapper::toDomain);
    }

    @Override
    public Optional<TradeExecutionRecord> findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccount(
            String instrument, String timeframe, ExecutionTriggerSource triggerSource, String brokerAccountId) {
        if (instrument == null || instrument.isBlank()
                || timeframe == null || timeframe.isBlank()
                || triggerSource == null
                || brokerAccountId == null || brokerAccountId.isBlank()) {
            return Optional.empty();
        }
        return repository.findActiveByInstrumentAndTimeframeAndTriggerSourceAndAccountRaw(
                instrument, timeframe, triggerSource, brokerAccountId, TERMINAL_STATUSES).stream()
            .findFirst()
            .map(TradeExecutionEntityMapper::toDomain);
    }

    @Override
    public List<TradeExecutionRecord> findPendingByTriggerSource(ExecutionTriggerSource triggerSource) {
        if (triggerSource == null) {
            return List.of();
        }
        return repository.findAllByTriggerSourceAndStatus(triggerSource, ExecutionStatus.PENDING_ENTRY_SUBMISSION).stream()
            .map(TradeExecutionEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<TradeExecutionRecord> findByTriggerSourceAndStatus(ExecutionTriggerSource triggerSource,
                                                                   ExecutionStatus status) {
        if (triggerSource == null || status == null) {
            return List.of();
        }
        return repository.findAllByTriggerSourceAndStatus(triggerSource, status).stream()
            .map(TradeExecutionEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<TradeExecutionRecord> findAllActive() {
        return repository.findAllByStatusNotIn(TERMINAL_STATUSES).stream()
            .map(TradeExecutionEntityMapper::toDomain)
            .toList();
    }
}
