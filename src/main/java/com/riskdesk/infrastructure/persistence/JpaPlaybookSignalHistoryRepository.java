package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.PlaybookSignalEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaPlaybookSignalHistoryRepository extends JpaRepository<PlaybookSignalEntity, UUID> {
    List<PlaybookSignalEntity> findByInstrumentOrderByEvaluatedAtDesc(String instrument, Pageable pageable);
    List<PlaybookSignalEntity> findByInstrumentAndTimeframeOrderByEvaluatedAtDesc(String instrument, String timeframe, Pageable pageable);
}
