package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.MentorSignalReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MentorSignalReviewJpaRepository extends JpaRepository<MentorSignalReviewEntity, Long> {

    boolean existsByAlertKey(String alertKey);

    List<MentorSignalReviewEntity> findByAlertKeyOrderByRevisionAsc(String alertKey);

    Optional<MentorSignalReviewEntity> findFirstByAlertKeyOrderByRevisionDesc(String alertKey);
}
