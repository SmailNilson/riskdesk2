package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.List;

/**
 * Port for fetching historical OHLCV candles from the internal market-data pipeline.
 * In this project the allowed source is IBKR only.
 */
public interface HistoricalDataProvider {
    List<Candle> fetchHistory(Instrument instrument, String timeframe, int count);
    boolean supports(Instrument instrument, String timeframe);

    /**
     * Fetches historical candles ending before the given time.
     * Used by the on-demand candle service to fill gaps when scrolling back.
     *
     * @param instrument the instrument to fetch
     * @param timeframe  bar size (e.g. "5m", "10m", "1h")
     * @param endTime    fetch bars ending at or before this instant
     * @param count      approximate number of bars to request
     * @return candles ordered oldest-first; empty list if unsupported or unavailable
     */
    default List<Candle> fetchHistoryBefore(Instrument instrument, String timeframe, Instant endTime, int count) {
        return List.of();
    }
}
