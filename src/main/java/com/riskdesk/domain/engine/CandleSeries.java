package com.riskdesk.domain.engine;

import com.riskdesk.domain.model.Candle;
import java.util.List;

/**
 * Immutable wrapper around a list of candles for safe domain access.
 * Once constructed, the underlying data cannot be modified.
 */
public final class CandleSeries {

    private final List<Candle> candles;

    public CandleSeries(List<Candle> candles) {
        this.candles = List.copyOf(candles);
    }

    /** Return an unmodifiable view of the candle list. */
    public List<Candle> asList() {
        return candles;
    }

    /** Number of candles in the series. */
    public int size() {
        return candles.size();
    }

    /** True if the series contains no candles. */
    public boolean isEmpty() {
        return candles.isEmpty();
    }

    /** Get the candle at the specified index. */
    public Candle get(int index) {
        return candles.get(index);
    }

    /** Get the most recent candle, or null if the series is empty. */
    public Candle latest() {
        return candles.isEmpty() ? null : candles.get(candles.size() - 1);
    }

    /**
     * Return a new CandleSeries containing the last {@code n} candles.
     * If {@code n} is greater than or equal to the series size, returns this instance.
     */
    public CandleSeries tail(int n) {
        if (n >= candles.size()) return this;
        return new CandleSeries(candles.subList(candles.size() - n, candles.size()));
    }
}
