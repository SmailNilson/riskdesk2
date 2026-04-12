package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.FootprintBarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface JpaFootprintBarRepository extends JpaRepository<FootprintBarEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM FootprintBarEntity f WHERE f.barTimestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);

    Optional<FootprintBarEntity> findByInstrumentAndTimeframeAndBarTimestamp(
            Instrument instrument, String timeframe, Instant barTimestamp);
}
