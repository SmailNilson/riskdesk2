package com.riskdesk.infrastructure.persistence;

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
}
