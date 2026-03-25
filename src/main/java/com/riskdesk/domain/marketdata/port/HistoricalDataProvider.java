package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;

import java.util.List;

/**
 * Port for fetching historical OHLCV candles from the internal market-data pipeline.
 * In this project the allowed source is IBKR only.
 */
public interface HistoricalDataProvider {
    List<Candle> fetchHistory(Instrument instrument, String timeframe, int count);
    boolean supports(Instrument instrument, String timeframe);
}
