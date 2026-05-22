package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.PlaybookDecisionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JpaPlaybookDecisionRepository extends JpaRepository<PlaybookDecisionEntity, Long> {
    Optional<PlaybookDecisionEntity> findByDecisionKey(String decisionKey);

    List<PlaybookDecisionEntity> findByInstrumentAndTimeframeOrderByCreatedAtDesc(
        String instrument,
        String timeframe,
        Pageable pageable
    );
}
