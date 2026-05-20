package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.CycleEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface JpaCycleEventRepository extends JpaRepository<CycleEventEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM CycleEventEntity e WHERE e.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);

    List<CycleEventEntity> findByInstrumentOrderByTimestampDesc(Instrument instrument, Pageable pageable);

    List<CycleEventEntity> findByInstrumentAndConfidenceGreaterThanEqualOrderByTimestampDesc(
        Instrument instrument, int minConfidence, Pageable pageable);
}
