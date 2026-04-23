package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.DistributionEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface JpaDistributionEventRepository extends JpaRepository<DistributionEventEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM DistributionEventEntity e WHERE e.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);

    List<DistributionEventEntity> findByInstrumentOrderByTimestampDesc(Instrument instrument, Pageable pageable);
}
