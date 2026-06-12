package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.CvdDivergencePaperTradeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface JpaCvdDivergencePaperTradeRepository
        extends JpaRepository<CvdDivergencePaperTradeEntity, Long> {

    Optional<CvdDivergencePaperTradeEntity> findFirstByInstrumentAndStatusOrderByEntryTimeDesc(
        Instrument instrument, String status);

    List<CvdDivergencePaperTradeEntity> findByStatus(String status);

    List<CvdDivergencePaperTradeEntity> findByInstrumentAndEntryTimeAfterOrderByEntryTimeDesc(
        Instrument instrument, Instant since);

    @Modifying
    @Transactional
    @Query("DELETE FROM CvdDivergencePaperTradeEntity e WHERE e.entryTime < :cutoff")
    int deleteByEntryTimeBefore(Instant cutoff);
}
