package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import java.time.Instant;
import java.util.List;

/**
 * Domain port for candle persistence.
 * Application services depend on this interface rather than on Spring Data repositories directly,
 * enabling hexagonal architecture and testability.
 */
public interface CandleRepositoryPort {

    List<Candle> findCandles(Instrument instrument, String timeframe, Instant from);

    List<Candle> findRecentCandles(Instrument instrument, String timeframe, int limit);

    /**
     * Fetches recent candles tagged with a specific contract month.
     * This is the primary query for all live indicator and analysis services.
     * Falls back to {@link #findRecentCandles} for legacy untagged data.
     */
    List<Candle> findRecentCandlesByContractMonth(Instrument instrument, String timeframe,
                                                  String contractMonth, int limit);

    Candle save(Candle candle);

    List<Candle> saveAll(List<Candle> candles);

    void deleteAll();

    void deleteByInstrumentAndTimeframe(Instrument instrument, String timeframe);

    long count();
}
