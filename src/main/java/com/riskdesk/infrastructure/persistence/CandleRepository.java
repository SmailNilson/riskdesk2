package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.CandleEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CandleRepository extends JpaRepository<CandleEntity, Long> {

    List<CandleEntity> findByInstrumentAndTimeframeAndTimestampGreaterThanEqualOrderByTimestampAsc(
            Instrument instrument, String timeframe, Instant from);

    void deleteByInstrumentAndTimeframe(Instrument instrument, String timeframe);

    /** Fetches exactly {@code pageable.getPageSize()} candles newest-first (use PageRequest.of(0, limit)). */
    List<CandleEntity> findByInstrumentAndTimeframeOrderByTimestampDesc(
            Instrument instrument, String timeframe, Pageable pageable);

    /** High-water mark: returns the most recent timestamp for an instrument/timeframe pair. */
    @Query("SELECT MAX(c.timestamp) FROM CandleEntity c WHERE c.instrument = :instrument AND c.timeframe = :timeframe")
    Optional<Instant> findMaxTimestamp(@Param("instrument") Instrument instrument, @Param("timeframe") String timeframe);

    /** Fetches candles within a time range, ordered oldest-first. */
    List<CandleEntity> findByInstrumentAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
            Instrument instrument, String timeframe, Instant from, Instant to);

    /** Fetches candles for a specific contract month, newest-first. */
    List<CandleEntity> findByInstrumentAndTimeframeAndContractMonthOrderByTimestampDesc(
            Instrument instrument, String timeframe, String contractMonth, Pageable pageable);

    /** Counts candles for a given instrument/timeframe pair. */
    long countByInstrumentAndTimeframe(Instrument instrument, String timeframe);
}
