package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.TickBarEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface JpaTickBarRepository extends JpaRepository<TickBarEntity, Long> {

    /** Newest bars first for an instrument/bar-size; caller reverses to oldest-first. */
    List<TickBarEntity> findByInstrumentAndTicksPerBarOrderBySeqDesc(
            Instrument instrument, int ticksPerBar, Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM TickBarEntity t WHERE t.instrument = :instrument")
    int deleteByInstrument(Instrument instrument);

    @Modifying
    @Transactional
    @Query("DELETE FROM TickBarEntity t WHERE t.closeAt < :cutoff")
    int deleteByCloseAtBefore(Instant cutoff);
}
