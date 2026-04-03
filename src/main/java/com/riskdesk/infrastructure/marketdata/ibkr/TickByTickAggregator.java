package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong totalBuyVolume = new AtomicLong(0);
    private final AtomicLong totalSellVolume = new AtomicLong(0);

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

        if (classification == TickClassification.BUY) {
            totalBuyVolume.addAndGet(size);
        } else if (classification == TickClassification.SELL) {
            totalSellVolume.addAndGet(size);
        }

        lastPrice = price;
        if (Double.isNaN(firstPriceInWindow)) {
            firstPriceInWindow = price;
        }

        evictExpired(timestamp);
    }

    /**
     * Get the current aggregation snapshot. Thread-safe read.
     */
    public TickAggregation snapshot() {
        Instant now = Instant.now();
        evictExpired(now);

        long buyVol = 0;
        long sellVol = 0;
        double firstPrice = Double.NaN;
        double latestPrice = Double.NaN;
        Instant windowStart = null;
        Instant windowEnd = null;

        for (ClassifiedTick tick : ticks) {
            if (windowStart == null) {
                windowStart = tick.timestamp();
                firstPrice = tick.price();
            }
            windowEnd = tick.timestamp();
            latestPrice = tick.price();

            if (tick.classification() == TickClassification.BUY) {
                buyVol += tick.size();
            } else if (tick.classification() == TickClassification.SELL) {
                sellVol += tick.size();
            }
        }

        if (windowStart == null) {
            return new TickAggregation(instrument, 0, 0, 0, 0, 0.0,
                TickAggregation.TREND_FLAT, false, null,
                now, now, TickAggregation.SOURCE_REAL_TICKS);
        }

        long delta = buyVol - sellVol;
        long cumulativeDelta = delta; // cumulative within the window

        // Buy ratio
        double totalVol = buyVol + sellVol;
        double buyRatio = totalVol > 0 ? (buyVol * 100.0 / totalVol) : 50.0;

        // Delta trend (compare current vs previous snapshot)
        String deltaTrend;
        if (previousCumulativeDelta == 0 || Math.abs(delta - previousCumulativeDelta) < Math.abs(previousCumulativeDelta * DELTA_TREND_THRESHOLD)) {
            deltaTrend = TickAggregation.TREND_FLAT;
        } else if (delta > previousCumulativeDelta) {
            deltaTrend = TickAggregation.TREND_RISING;
        } else {
            deltaTrend = TickAggregation.TREND_FALLING;
        }
        previousCumulativeDelta = delta;

        // Divergence detection: price up but delta down (bearish) or price down but delta up (bullish)
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
            Math.round(buyRatio * 10.0) / 10.0, // round to 1 decimal
            deltaTrend, divergenceDetected, divergenceType,
            windowStart, windowEnd, TickAggregation.SOURCE_REAL_TICKS);
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
