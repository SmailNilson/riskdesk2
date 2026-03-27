package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.model.IbkrWatchlistInstrument;
import com.riskdesk.domain.model.WatchlistCandle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface WatchlistInstrumentMarketDataPort {

    List<WatchlistCandle> fetchHistory(IbkrWatchlistInstrument instrument, String timeframe, int count);

    Optional<BigDecimal> fetchLatestPrice(IbkrWatchlistInstrument instrument);
}
