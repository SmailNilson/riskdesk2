package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface CandleRepository extends JpaRepository<Candle, Long> {

    List<Candle> findByInstrumentAndTimeframeAndTimestampGreaterThanEqualOrderByTimestampAsc(
            Instrument instrument, String timeframe, Instant from);

    void deleteByInstrumentAndTimeframe(Instrument instrument, String timeframe);

    /** Fetches exactly {@code pageable.getPageSize()} candles newest-first (use PageRequest.of(0, limit)). */
    List<Candle> findByInstrumentAndTimeframeOrderByTimestampDesc(
            Instrument instrument, String timeframe, Pageable pageable);
}
