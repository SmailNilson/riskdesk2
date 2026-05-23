package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.WtxRsiSignalHistoryEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaWtxRsiSignalHistoryRepository
        extends JpaRepository<WtxRsiSignalHistoryEntity, Long> {

    List<WtxRsiSignalHistoryEntity> findByInstrumentOrderBySignalTsDesc(String instrument, Pageable pageable);

    List<WtxRsiSignalHistoryEntity> findByInstrumentAndTimeframeOrderBySignalTsDesc(
            String instrument, String timeframe, Pageable pageable);
}
