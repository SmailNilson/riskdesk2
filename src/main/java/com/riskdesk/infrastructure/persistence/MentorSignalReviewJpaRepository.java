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

    @Query("SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END FROM MentorSignalReviewEntity e " +
           "WHERE e.instrument = ?1 AND e.category = ?2 AND e.action = ?3 " +
           "AND e.createdAt > ?4 AND e.status IN ('DONE', 'ANALYZING')")
    boolean existsRecentReview(String instrument, String category, String action, Instant since);

    @Query("SELECT e FROM MentorSignalReviewEntity e " +
           "WHERE e.instrument = ?1 AND e.status = 'DONE' AND e.sourceType = 'SIGNAL' AND e.createdAt > ?2 " +
           "ORDER BY e.createdAt DESC")
    List<MentorSignalReviewEntity> findRecentByInstrumentAndCreatedAtAfter(String instrument, Instant since);

    @Modifying
    @Query("UPDATE MentorSignalReviewEntity e SET e.status = 'ERROR', e.completedAt = ?2, e.errorMessage = ?1 WHERE e.status = 'ANALYZING'")
    int markAnalyzingAsError(String errorMessage, Instant completedAt);
}
