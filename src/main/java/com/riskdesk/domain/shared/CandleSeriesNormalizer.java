package com.riskdesk.domain.shared;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;

import java.util.List;

/**
 * Filters raw candle series before they reach indicator computations or the
 * chart endpoint.
 * <p>
 * Implements the "Store Raw, Filter on Read" principle: the database keeps
 * every candle IBKR sends (including stale weekend ticks), and this class
 * strips out-of-session candles at read time so that indicators and charts
 * only see market-hours data.
 */
public final class CandleSeriesNormalizer {

    private CandleSeriesNormalizer() {}

    /**
     * Returns a new list containing only candles whose timestamp falls within
     * CME market hours (Sunday 17:00 ET → Friday 17:00 ET).
     * <p>
     * The returned list preserves the input ordering (oldest→newest).
     *
     * @param candles    raw candle series from the database
     * @param instrument the instrument (synthetic instruments are never filtered)
     * @return filtered list — may be smaller than input, never null
     */
    public static List<Candle> purgeOutOfSession(List<Candle> candles, Instrument instrument) {
        if (instrument.isSynthetic()) return candles;
        return candles.stream()
                .filter(c -> TradingSessionResolver.isMarketOpen(c.getTimestamp()))
                .toList();
    }
}
