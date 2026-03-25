package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * JPA adapter that bridges the domain port to the Spring Data repository.
 */
@Component
public class JpaCandleRepositoryAdapter implements CandleRepositoryPort {

    private final CandleRepository springDataRepo;

    public JpaCandleRepositoryAdapter(CandleRepository springDataRepo) {
        this.springDataRepo = springDataRepo;
    }

    @Override
    public List<Candle> findCandles(Instrument instrument, String timeframe, Instant from) {
        return springDataRepo.findByInstrumentAndTimeframeAndTimestampGreaterThanEqualOrderByTimestampAsc(
                instrument, timeframe, from);
    }

    @Override
    public List<Candle> findRecentCandles(Instrument instrument, String timeframe, int limit) {
        // Use Pageable to push the LIMIT to the database — avoids fetching up to 500 rows in-memory
        return springDataRepo.findByInstrumentAndTimeframeOrderByTimestampDesc(
                instrument, timeframe, PageRequest.of(0, limit));
    }

    @Override
    public Candle save(Candle candle) {
        return springDataRepo.save(candle);
    }

    @Override
    public List<Candle> saveAll(List<Candle> candles) {
        return springDataRepo.saveAll(candles);
    }

    @Override
    @Transactional
    public void deleteAll() {
        springDataRepo.deleteAll();
    }

    @Override
    @Transactional
    public void deleteByInstrumentAndTimeframe(Instrument instrument, String timeframe) {
        springDataRepo.deleteByInstrumentAndTimeframe(instrument, timeframe);
    }

    @Override
    public long count() {
        return springDataRepo.count();
    }
}
