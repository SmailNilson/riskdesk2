package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.CandleEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Fetches up to {@code pageable.getPageSize()} candles within a time range, oldest-first.
     * Backs cursor pagination for the range read endpoint (use PageRequest.of(0, limit)).
     */
    List<CandleEntity> findByInstrumentAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
            Instrument instrument, String timeframe, Instant from, Instant to, Pageable pageable);

    /** Fetches candles for a specific contract month, newest-first. */
    List<CandleEntity> findByInstrumentAndTimeframeAndContractMonthOrderByTimestampDesc(
            Instrument instrument, String timeframe, String contractMonth, Pageable pageable);

    /** Natural-key lookup backing the adapter's upsert (uk_candle_instrument_tf_ts). */
    Optional<CandleEntity> findByInstrumentAndTimeframeAndTimestamp(
            Instrument instrument, String timeframe, Instant timestamp);

    /** Bulk-deletes candles inside the closed range [from, to]; returns rows removed. */
    @Modifying
    @Query("DELETE FROM CandleEntity c WHERE c.instrument = :instrument AND c.timeframe = :timeframe"
            + " AND c.timestamp >= :from AND c.timestamp <= :to")
    int deleteRange(@Param("instrument") Instrument instrument, @Param("timeframe") String timeframe,
                    @Param("from") Instant from, @Param("to") Instant to);
}
