package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /**
     * Returns the most recent candle timestamp stored for a given instrument/timeframe pair.
     * Used as high-water mark for incremental gap-fill fetches.
     */
    Optional<Instant> findLatestTimestamp(Instrument instrument, String timeframe);

    /**
     * Returns candles within a time range, ordered oldest-first.
     * Used by the on-demand candle service for scroll-back and range queries.
     */
    List<Candle> findCandlesBetween(Instrument instrument, String timeframe, Instant from, Instant to);

    /**
     * Returns at most {@code limit} candles within a time range, ordered oldest-first.
     * Used by the cursor-paginated range endpoint to stream large 1m windows for
     * backtests without breaching the 1000-candle chart cap. The caller advances the
     * cursor by re-querying with {@code from} set just after the last returned timestamp.
     */
    List<Candle> findCandlesBetweenPaged(Instrument instrument, String timeframe,
                                         Instant from, Instant to, int limit);

    Candle save(Candle candle);

    List<Candle> saveAll(List<Candle> candles);

    void deleteAll();

    void deleteByInstrumentAndTimeframe(Instrument instrument, String timeframe);

    /**
     * Deletes every candle for the pair inside the closed range {@code [from, to]}.
     * Used by the replace-mode backfill to purge a window before refilling it from a
     * better source (e.g. re-sourcing back-month bars with the continuous contract).
     *
     * @return number of candles deleted
     */
    int deleteRange(Instrument instrument, String timeframe, Instant from, Instant to);

    long count();
}
