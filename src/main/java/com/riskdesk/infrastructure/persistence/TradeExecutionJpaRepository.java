package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.infrastructure.persistence.entity.TradeExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TradeExecutionJpaRepository extends JpaRepository<TradeExecutionEntity, Long> {

    Optional<TradeExecutionEntity> findByMentorSignalReviewId(Long mentorSignalReviewId);

    List<TradeExecutionEntity> findAllByMentorSignalReviewIdIn(Collection<Long> mentorSignalReviewIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from TradeExecutionEntity e where e.id = :id")
    Optional<TradeExecutionEntity> findByIdForUpdate(@Param("id") Long id);

    Optional<TradeExecutionEntity> findByIbkrOrderId(Integer ibkrOrderId);

    Optional<TradeExecutionEntity> findByExecutionKey(String executionKey);

    /**
     * PR #303 — return the most recent non-terminal execution for an
     * instrument (regardless of trigger source). Used to gate auto-arm so we
     * never duplicate an active position. Terminal statuses (CLOSED,
     * CANCELLED, REJECTED, FAILED) are excluded.
     */
    @Query("select e from TradeExecutionEntity e " +
           "where e.instrument = :instrument " +
           "  and e.status not in (:terminal) " +
           "order by e.createdAt desc")
    List<TradeExecutionEntity> findActiveByInstrumentRaw(@Param("instrument") String instrument,
                                                          @Param("terminal") Collection<ExecutionStatus> terminalStatuses);

    /**
     * PR #303 — return all currently-pending executions for the given trigger
     * source (e.g. {@link ExecutionTriggerSource#QUANT_AUTO_ARM}). Used by the
     * auto-submit scheduler to find decisions whose cancel window has elapsed.
     */
    List<TradeExecutionEntity> findAllByTriggerSourceAndStatus(ExecutionTriggerSource triggerSource,
                                                                ExecutionStatus status);

    /**
     * Active Positions Panel — return every execution whose status is NOT in
     * the supplied terminal-status set. Ordered most-recent-first so the panel
     * naturally shows newer trades on top.
     */
    @Query("select e from TradeExecutionEntity e where e.status not in (:statuses) order by e.createdAt desc")
    List<TradeExecutionEntity> findAllByStatusNotIn(@Param("statuses") Collection<ExecutionStatus> statuses);
}
