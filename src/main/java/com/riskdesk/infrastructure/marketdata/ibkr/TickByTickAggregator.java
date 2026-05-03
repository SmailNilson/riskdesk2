package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Aggregates classified trade ticks into a rolling time window for order flow analysis.
 * Thread-safe: ticks arrive on the IBKR EReader thread, reads happen from application threads.
 */
public class TickByTickAggregator {

    private static final long DEFAULT_WINDOW_SECONDS = 300; // 5 minutes
    private static final double DELTA_TREND_THRESHOLD = 0.05; // 5% change to be non-flat

    private final Instrument instrument;
    private final long windowSeconds;
    private final ConcurrentLinkedDeque<ClassifiedTick> ticks = new ConcurrentLinkedDeque<>();

    // For divergence detection: track price direction over the window
    private volatile double firstPriceInWindow = Double.NaN;
    private volatile double lastPrice = Double.NaN;
    private volatile long previousCumulativeDelta = 0;

    public TickByTickAggregator(Instrument instrument) {
        this(instrument, DEFAULT_WINDOW_SECONDS);
    }

    public TickByTickAggregator(Instrument instrument, long windowSeconds) {
        this.instrument = instrument;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Record a classified trade tick. Called from the IBKR EReader thread.
     */
    public void onTick(double price, long size, TickClassification classification, Instant timestamp) {
        if (classification == TickClassification.UNCLASSIFIED) {
            return; // Skip unclassified ticks
        }
        ClassifiedTick tick = new ClassifiedTick(price, size, classification, timestamp);
        ticks.addLast(tick);

        lastPrice = price;
        if (Double.isNaN(firstPriceInWindow)) {
            firstPriceInWindow = price;
        }

        evictExpired(timestamp);
    }

    /**
     * Get the current aggregation snapshot over the full rolling window (default 5 min). Thread-safe read.
     * <p>
     * Updates the {@code previousCumulativeDelta} state used for the deltaTrend calculation —
     * call only from a single scheduler thread.
     */
    public TickAggregation snapshot() {
        Instant now = Instant.now();
        evictExpired(now);
        return aggregateSince(null, now, true);
    }

    /**
     * Get an aggregation snapshot over only the last {@code windowSeconds} of ticks.
     * Used for short-window detectors (e.g. absorption needs a tight window to detect
     * transient events; the default 5 min snapshot dilutes them).
     * <p>
     * Does NOT mutate {@code previousCumulativeDelta} — that belongs to the long-window snapshot.
     */
    public TickAggregation snapshotWindow(long windowSeconds) {
        Instant now = Instant.now();
        evictExpired(now);
        Instant cutoff = now.minusSeconds(windowSeconds);
        return aggregateSince(cutoff, now, false);
    }

    /**
     * Iterate ticks newer than {@code cutoff} (or all ticks if cutoff is null) and build an aggregation.
     * @param updateTrendState if true, mutates {@code previousCumulativeDelta} for deltaTrend tracking.
     */
    private TickAggregation aggregateSince(Instant cutoff, Instant now, boolean updateTrendState) {
        long buyVol = 0;
        long sellVol = 0;
        double firstPrice = Double.NaN;
        double latestPrice = Double.NaN;
        double highPrice = Double.NaN;
        double lowPrice = Double.NaN;
        Instant windowStart = null;
        Instant windowEnd = null;

        for (ClassifiedTick tick : ticks) {
            if (cutoff != null && tick.timestamp().isBefore(cutoff)) continue;
            if (windowStart == null) {
                windowStart = tick.timestamp();
                firstPrice = tick.price();
                highPrice = tick.price();
                lowPrice = tick.price();
            }
            windowEnd = tick.timestamp();
            latestPrice = tick.price();
            if (tick.price() > highPrice) highPrice = tick.price();
            if (tick.price() < lowPrice) lowPrice = tick.price();

            if (tick.classification() == TickClassification.BUY) {
                buyVol += tick.size();
            } else if (tick.classification() == TickClassification.SELL) {
                sellVol += tick.size();
            }
        }

        if (windowStart == null) {
            return new TickAggregation(instrument, 0, 0, 0, 0, 0.0,
                TickAggregation.TREND_FLAT, false, null,
                now, now, TickAggregation.SOURCE_REAL_TICKS,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN);
        }

        long delta = buyVol - sellVol;
        long cumulativeDelta = delta; // cumulative within the window

        double totalVol = buyVol + sellVol;
        double buyRatio = totalVol > 0 ? (buyVol * 100.0 / totalVol) : 50.0;

        String deltaTrend;
        if (previousCumulativeDelta == 0 || Math.abs(delta - previousCumulativeDelta) < Math.abs(previousCumulativeDelta * DELTA_TREND_THRESHOLD)) {
            deltaTrend = TickAggregation.TREND_FLAT;
        } else if (delta > previousCumulativeDelta) {
            deltaTrend = TickAggregation.TREND_RISING;
        } else {
            deltaTrend = TickAggregation.TREND_FALLING;
        }
        if (updateTrendState) {
            previousCumulativeDelta = delta;
        }

        boolean divergenceDetected = false;
        String divergenceType = null;
        if (!Double.isNaN(firstPrice) && !Double.isNaN(latestPrice) && totalVol > 0) {
            boolean priceUp = latestPrice > firstPrice;
            boolean priceDown = latestPrice < firstPrice;
            boolean deltaPositive = delta > 0;
            boolean deltaNegative = delta < 0;

            if (priceUp && deltaNegative) {
                divergenceDetected = true;
                divergenceType = TickAggregation.DIVERGENCE_BEARISH;
            } else if (priceDown && deltaPositive) {
                divergenceDetected = true;
                divergenceType = TickAggregation.DIVERGENCE_BULLISH;
            }
        }

        return new TickAggregation(instrument, buyVol, sellVol, delta, cumulativeDelta,
            Math.round(buyRatio * 10.0) / 10.0,
            deltaTrend, divergenceDetected, divergenceType,
            windowStart, windowEnd, TickAggregation.SOURCE_REAL_TICKS,
            highPrice, lowPrice,
            firstPrice, latestPrice);
    }

    /**
     * Remove ticks older than the window.
     */
    private void evictExpired(Instant now) {
        Instant cutoff = now.minusSeconds(windowSeconds);
        while (!ticks.isEmpty()) {
            ClassifiedTick oldest = ticks.peekFirst();
            if (oldest != null && oldest.timestamp().isBefore(cutoff)) {
                ticks.pollFirst();
            } else {
                break;
            }
        }
        // Reset first price tracking if window is empty
        if (ticks.isEmpty()) {
            firstPriceInWindow = Double.NaN;
        }
    }

    public boolean hasData() {
        return !ticks.isEmpty();
    }

    /** Tick classification based on Lee-Ready rule. */
    public enum TickClassification {
        BUY,    // Trade at or above ask
        SELL,   // Trade at or below bid
        UNCLASSIFIED
    }

    /** A classified trade tick. */
    record ClassifiedTick(double price, long size, TickClassification classification, Instant timestamp) {}
}
