package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.model.WatchlistCandle;

import java.util.List;

public interface WatchlistCandleRepositoryPort {

    List<WatchlistCandle> findRecentCandles(String instrumentCode, String timeframe, int limit);

    List<WatchlistCandle> saveAll(List<WatchlistCandle> candles);

    void deleteByInstrumentCodeAndTimeframe(String instrumentCode, String timeframe);
}
