package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.MentorSignalReviewEntity;
import com.riskdesk.domain.model.TradeSimulationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MentorSignalReviewJpaRepository extends JpaRepository<MentorSignalReviewEntity, Long> {

    boolean existsByAlertKey(String alertKey);

    List<MentorSignalReviewEntity> findByAlertKeyOrderByRevisionAsc(String alertKey);

    Optional<MentorSignalReviewEntity> findFirstByAlertKeyOrderByRevisionDesc(String alertKey);

    List<MentorSignalReviewEntity> findBySimulationStatusInOrderByCreatedAtAsc(List<TradeSimulationStatus> statuses);

    long deleteByStatusIn(List<String> statuses);

    @Modifying
    @Query("UPDATE MentorSignalReviewEntity e SET e.status = 'ERROR', e.completedAt = ?2, e.errorMessage = ?1 WHERE e.status = 'ANALYZING'")
    int markAnalyzingAsError(String errorMessage, Instant completedAt);
}
