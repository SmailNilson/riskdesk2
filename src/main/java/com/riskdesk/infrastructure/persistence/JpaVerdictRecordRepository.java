package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.VerdictRecordEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface JpaVerdictRecordRepository extends JpaRepository<VerdictRecordEntity, Long> {

    Optional<VerdictRecordEntity> findFirstByInstrumentAndTimeframeOrderByDecisionTimestampDesc(
        Instrument instrument, String timeframe);

    List<VerdictRecordEntity> findByInstrumentAndTimeframeOrderByDecisionTimestampDesc(
        Instrument instrument, String timeframe, Pageable pageable);

    List<VerdictRecordEntity> findByInstrumentAndTimeframeAndDecisionTimestampBetweenOrderByDecisionTimestampAsc(
        Instrument instrument, String timeframe, Instant from, Instant to);

    @Modifying
    @Transactional
    @Query("DELETE FROM VerdictRecordEntity v WHERE v.decisionTimestamp < :cutoff")
    int deleteByDecisionTimestampBefore(Instant cutoff);
}
