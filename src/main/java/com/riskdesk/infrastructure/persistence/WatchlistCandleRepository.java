package com.riskdesk.infrastructure.persistence;

import com.riskdesk.infrastructure.persistence.entity.WatchlistCandleEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WatchlistCandleRepository extends JpaRepository<WatchlistCandleEntity, Long> {

    List<WatchlistCandleEntity> findByInstrumentCodeAndTimeframeOrderByTimestampDesc(
        String instrumentCode,
        String timeframe,
        Pageable pageable
    );

    void deleteByInstrumentCodeAndTimeframe(String instrumentCode, String timeframe);
}
