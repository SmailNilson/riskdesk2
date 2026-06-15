package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.CvdDivergenceEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface JpaCvdDivergenceEventRepository extends JpaRepository<CvdDivergenceEventEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM CvdDivergenceEventEntity e WHERE e.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);

    List<CvdDivergenceEventEntity> findByInstrumentOrderByTimestampDesc(Instrument instrument, Pageable pageable);
}
