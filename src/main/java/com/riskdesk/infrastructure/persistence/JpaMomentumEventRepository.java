package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.MomentumEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface JpaMomentumEventRepository extends JpaRepository<MomentumEventEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM MomentumEventEntity e WHERE e.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);

    List<MomentumEventEntity> findByInstrumentOrderByTimestampDesc(Instrument instrument, Pageable pageable);
}
