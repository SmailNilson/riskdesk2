package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.entity.CandleEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface CandleRepository extends JpaRepository<CandleEntity, Long> {

    List<CandleEntity> findByInstrumentAndTimeframeAndTimestampGreaterThanEqualOrderByTimestampAsc(
            Instrument instrument, String timeframe, Instant from);

    List<CandleEntity> findByInstrumentAndTimeframeAndContractMonthAndTimestampGreaterThanEqualOrderByTimestampAsc(
            Instrument instrument, String timeframe, String contractMonth, Instant from);

    void deleteByInstrumentAndTimeframe(Instrument instrument, String timeframe);

    /** Fetches exactly {@code pageable.getPageSize()} candles newest-first (use PageRequest.of(0, limit)). */
    List<CandleEntity> findByInstrumentAndTimeframeOrderByTimestampDesc(
            Instrument instrument, String timeframe, Pageable pageable);

    List<CandleEntity> findByInstrumentAndTimeframeAndContractMonthOrderByTimestampDesc(
            Instrument instrument, String timeframe, String contractMonth, Pageable pageable);
}
