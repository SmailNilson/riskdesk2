package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.IcebergEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface JpaIcebergEventRepository extends JpaRepository<IcebergEventEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM IcebergEventEntity e WHERE e.timestamp < :cutoff")
    int deleteByTimestampBefore(Instant cutoff);

    /**
     * Returns the most recent iceberg events for the given instrument, newest first.
     * Use {@link Pageable} to cap the result count (e.g. {@code PageRequest.of(0, 20)}).
     */
    List<IcebergEventEntity> findByInstrumentOrderByTimestampDesc(Instrument instrument, Pageable pageable);
}
