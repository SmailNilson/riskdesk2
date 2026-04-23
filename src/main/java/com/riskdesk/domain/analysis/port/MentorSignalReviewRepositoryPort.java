package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.model.MentorSignalReviewRecord;

import java.util.List;
import java.util.Optional;

public interface MentorSignalReviewRepositoryPort {

    MentorSignalReviewRecord save(MentorSignalReviewRecord review);

    Optional<MentorSignalReviewRecord> findById(Long id);

    boolean existsByAlertKey(String alertKey);

    List<MentorSignalReviewRecord> findByAlertKeyOrderByRevisionAsc(String alertKey);

    Optional<MentorSignalReviewRecord> findLatestByAlertKey(String alertKey);

    List<MentorSignalReviewRecord> findRecent(int limit);

    long deleteByStatuses(List<String> statuses);

    /**
     * Semantic dedup: checks if a recent review exists for the same instrument, category
     * and action (direction), regardless of timestamp. Used as a safety net to prevent
     * duplicate Gemini API calls after restarts.
     */
    boolean existsRecentReview(String instrument, String category, String action, java.time.Instant since);

    /** Mark all reviews stuck in ANALYZING as ERROR (orphaned by server restart). */
    int markAnalyzingAsError(String errorMessage);

    /** Mark reviews stuck in ANALYZING created before cutoff as ERROR (runtime safety net). */
    int markStaleAnalyzingAsError(String errorMessage, java.time.Instant createdBefore);
}
