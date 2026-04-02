package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.model.MentorSignalReviewRecord;
import com.riskdesk.domain.model.TradeSimulationStatus;

import java.util.List;
import java.util.Optional;

public interface MentorSignalReviewRepositoryPort {

    MentorSignalReviewRecord save(MentorSignalReviewRecord review);

    Optional<MentorSignalReviewRecord> findById(Long id);

    boolean existsByAlertKey(String alertKey);

    List<MentorSignalReviewRecord> findByAlertKeyOrderByRevisionAsc(String alertKey);

    Optional<MentorSignalReviewRecord> findLatestByAlertKey(String alertKey);

    List<MentorSignalReviewRecord> findRecent(int limit);

    List<MentorSignalReviewRecord> findBySimulationStatuses(List<TradeSimulationStatus> statuses);

    long deleteByStatuses(List<String> statuses);
}
