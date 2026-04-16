package com.riskdesk.domain.engine;

import com.riskdesk.domain.model.Candle;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable wrapper around a chronologically-ordered list of candles for
 * safe domain access. Once constructed, the underlying data cannot be modified.
 *
 * <h2>Order contract</h2>
 * The constructor validates that:
 * <ol>
 *   <li>the candle list is non-null;</li>
 *   <li>no candle is null;</li>
 *   <li>no candle timestamp is null;</li>
 *   <li>timestamps are non-decreasing ({@code candles[i+1].timestamp >= candles[i].timestamp}).</li>
 * </ol>
 * Equal consecutive timestamps are permitted because real-time feeds
 * occasionally emit candles sharing a millisecond boundary — they are
 * unusual but not corrupting. Strictly decreasing pairs indicate a bug in
 * the upstream pipeline (wrong {@code ORDER BY}, off-by-one subList, stale
 * data injection) and must fail loudly at construction so the mistake is
 * caught inside the failing test or boot sequence rather than silently
 * corrupting indicator state downstream.
 */
public final class CandleSeries {

    private final List<Candle> candles;

    public CandleSeries(List<Candle> candles) {
        Objects.requireNonNull(candles, "candles must not be null");
        validateOrder(candles);
        this.candles = List.copyOf(candles);
    }

    private static void validateOrder(List<Candle> candles) {
        Instant previousTimestamp = null;
        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (candle == null) {
                throw new IllegalArgumentException(
                    "candles[" + i + "] is null — every slot must contain a candle");
            }
            Instant timestamp = candle.getTimestamp();
            if (timestamp == null) {
                throw new IllegalArgumentException(
                    "candles[" + i + "].timestamp is null — every candle must carry a timestamp");
            }
            if (previousTimestamp != null && timestamp.isBefore(previousTimestamp)) {
                throw new IllegalArgumentException(
                    "candles are not chronologically ordered: candles[" + i + "] timestamp "
                        + timestamp + " is before candles[" + (i - 1) + "] timestamp "
                        + previousTimestamp);
            }
            previousTimestamp = timestamp;
        }
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
