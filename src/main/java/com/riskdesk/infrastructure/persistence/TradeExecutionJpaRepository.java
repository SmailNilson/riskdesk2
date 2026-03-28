package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.TradeExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradeExecutionJpaRepository extends JpaRepository<TradeExecutionEntity, Long> {

    Optional<TradeExecutionEntity> findByMentorSignalReviewId(Long mentorSignalReviewId);
}
