package com.riskdesk.domain.engine.indicators;

import com.riskdesk.domain.model.Candle;
import java.util.List;

/**
 * Base interface for all technical indicators.
 * Each indicator analyzes candle data and produces typed results.
 *
 * @param <R> the result type produced by this indicator
 */
public interface TechnicalIndicator<R> {

    /**
     * Calculate the indicator for a series of candles.
     * Returns one result per candle (after any warmup period).
     */
    List<R> calculate(List<Candle> candles);

    /**
     * Get the current (latest) indicator value from the given candles.
     * Returns null if there is insufficient data.
     */
    R current(List<Candle> candles);
}
