package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.analysis.port.WatchlistCandleRepositoryPort;
import com.riskdesk.domain.model.WatchlistCandle;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class JpaWatchlistCandleRepositoryAdapter implements WatchlistCandleRepositoryPort {

    private final WatchlistCandleRepository repository;

    public JpaWatchlistCandleRepositoryAdapter(WatchlistCandleRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchlistCandle> findRecentCandles(String instrumentCode, String timeframe, int limit) {
        return repository.findByInstrumentCodeAndTimeframeOrderByTimestampDesc(
                instrumentCode,
                timeframe,
                PageRequest.of(0, limit)
            ).stream()
            .map(WatchlistCandleEntityMapper::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public List<WatchlistCandle> saveAll(List<WatchlistCandle> candles) {
        return repository.saveAll(candles.stream().map(WatchlistCandleEntityMapper::toEntity).toList()).stream()
            .map(WatchlistCandleEntityMapper::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public void deleteByInstrumentCodeAndTimeframe(String instrumentCode, String timeframe) {
        repository.deleteByInstrumentCodeAndTimeframe(instrumentCode, timeframe);
    }
}
