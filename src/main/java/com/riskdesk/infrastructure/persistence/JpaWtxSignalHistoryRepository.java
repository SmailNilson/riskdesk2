package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.WtxSignalHistoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaWtxSignalHistoryRepository extends JpaRepository<WtxSignalHistoryEntity, Long> {
    List<WtxSignalHistoryEntity> findByInstrumentOrderBySignalTsDesc(String instrument, Pageable pageable);
    List<WtxSignalHistoryEntity> findByInstrumentAndTimeframeOrderBySignalTsDesc(String instrument, String timeframe, Pageable pageable);
}
