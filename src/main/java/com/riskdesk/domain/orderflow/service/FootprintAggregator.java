package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.model.FootprintLevel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pure domain service that accumulates classified trade ticks into clock-aligned
 * footprint bars. NOT a Spring bean — instantiated per instrument by the
 * infrastructure adapter.
 *
 * <p>Bars are aligned to the wall clock: a tick at 14:07 with a 10-minute bar
 * belongs to the 14:00–14:10 bar. When a tick arrives in a new bar window, the
 * previous bar is closed and returned to the caller (tick-driven close); for idle
 * periods, {@link #closeIfElapsed} closes a bar whose window has fully passed.</p>
 *
 * <p>Prices are binned into buckets of {@code bucketSize} (e.g. 5.00 points for MNQ,
 * 0.05 for MCL — typically a multiple of the instrument's tick size). A trade is
 * attributed to the bucket whose lower bound it falls into: bucket = floor(price /
 * bucketSize) * bucketSize.</p>
 *
 * <p>Thread safety: external callers must synchronize if used from multiple threads.</p>
 */
public class FootprintAggregator {

    private static final double IMBALANCE_RATIO = 3.0;

    private final Instrument instrument;
    private final double bucketSize;
    private final int barSeconds;
    private final String timeframeLabel;

    // bucket lower bound -> {buyVol, sellVol}
    private final TreeMap<Double, long[]> currentBarLevels = new TreeMap<>();
    /** Epoch seconds of the current bar's open, aligned to barSeconds. -1 = no bar yet. */
    private long currentBarOpen = -1;

    public FootprintAggregator(Instrument instrument, double bucketSize, int barSeconds) {
        if (bucketSize <= 0) {
            throw new IllegalArgumentException("bucketSize must be positive, got: " + bucketSize);
        }
        if (barSeconds <= 0) {
            throw new IllegalArgumentException("barSeconds must be positive, got: " + barSeconds);
        }
        this.instrument = instrument;
        this.bucketSize = bucketSize;
        this.barSeconds = barSeconds;
        this.timeframeLabel = barSeconds % 3600 == 0
            ? (barSeconds / 3600) + "h"
            : (barSeconds / 60) + "m";
    }

    /**
     * Records a classified trade tick.
     *
     * @param price          the trade price
     * @param size           the number of contracts
     * @param classification "BUY" or "SELL" (anything else is ignored)
     * @param timestamp      the tick's trade timestamp (determines its bar)
     * @return the previous bar, closed because this tick opened a new bar window;
     *         empty otherwise
     */
    public Optional<FootprintBar> onTick(double price, long size, String classification, Instant timestamp) {
        if (size <= 0) return Optional.empty();
        if (!"BUY".equals(classification) && !"SELL".equals(classification)) return Optional.empty();

        long alignedOpen = Math.floorDiv(timestamp.getEpochSecond(), barSeconds) * barSeconds;

        Optional<FootprintBar> closed = Optional.empty();
        if (currentBarOpen >= 0 && alignedOpen > currentBarOpen) {
            if (!currentBarLevels.isEmpty()) {
                closed = Optional.of(snapshot());
            }
            currentBarLevels.clear();
        }
        if (currentBarOpen < 0 || alignedOpen > currentBarOpen) {
            currentBarOpen = alignedOpen;
        }
        // A late tick from an already-closed bar (alignedOpen < currentBarOpen) is rare
        // (out-of-order delivery); it is attributed to the current bar rather than dropped.

        double bucket = bucketFor(price);
        long[] volumes = currentBarLevels.computeIfAbsent(bucket, k -> new long[2]);
        if ("BUY".equals(classification)) {
            volumes[0] += size;
        } else {
            volumes[1] += size;
        }
        return closed;
    }

    /**
     * Closes the current bar if its window has fully elapsed (no tick has rolled it
     * over — quiet market). Returns the closed bar, or empty if the bar is still
     * in progress or has no data.
     */
    public Optional<FootprintBar> closeIfElapsed(Instant now) {
        if (currentBarOpen < 0 || currentBarLevels.isEmpty()) return Optional.empty();
        if (now.getEpochSecond() < currentBarOpen + barSeconds) return Optional.empty();
        FootprintBar bar = snapshot();
        currentBarLevels.clear();
        currentBarOpen = -1;
        return Optional.of(bar);
    }

    /**
     * Immutable snapshot of the current in-progress bar, or empty when no tick has
     * been recorded in the current bar window.
     */
    public Optional<FootprintBar> currentBar() {
        if (currentBarOpen < 0 || currentBarLevels.isEmpty()) return Optional.empty();
        return Optional.of(snapshot());
    }

    /**
     * Returns true if the aggregator has any tick data for the current bar.
     */
    public boolean hasData() {
        return !currentBarLevels.isEmpty();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private FootprintBar snapshot() {
        Map<Double, FootprintLevel> levels = new LinkedHashMap<>();
        long totalBuy = 0;
        long totalSell = 0;
        double pocPrice = 0;
        long pocVolume = 0;

        for (Map.Entry<Double, long[]> entry : currentBarLevels.entrySet()) {
            double price = entry.getKey();
            long buyVol = entry.getValue()[0];
            long sellVol = entry.getValue()[1];
            long delta = buyVol - sellVol;
            boolean imbalance = isImbalance(buyVol, sellVol);

            levels.put(price, new FootprintLevel(price, buyVol, sellVol, delta, imbalance));

            totalBuy += buyVol;
            totalSell += sellVol;

            long totalVol = buyVol + sellVol;
            if (totalVol > pocVolume) {
                pocVolume = totalVol;
                pocPrice = price;
            }
        }

        return new FootprintBar(
            instrument.name(),
            timeframeLabel,
            currentBarOpen,
            Map.copyOf(levels),
            pocPrice,
            totalBuy,
            totalSell,
            totalBuy - totalSell
        );
    }

    /**
     * Lower bound of the price bucket this trade falls into. The small epsilon guards
     * against floating-point representation pushing an exact bucket boundary down
     * (e.g. 29005.0 / 5.0 = 5800.999999... must still bucket to 29005, not 29000);
     * the result is normalized to 6 decimals so equal buckets produce identical keys.
     */
    private double bucketFor(double price) {
        long bucketIndex = (long) Math.floor(price / bucketSize + 1e-9);
        return Math.round(bucketIndex * bucketSize * 1e6) / 1e6;
    }

    /**
     * Imbalance detection: one side dominates at 3:1 ratio or more.
     */
    private static boolean isImbalance(long buyVolume, long sellVolume) {
        if (buyVolume == 0 && sellVolume == 0) return false;
        if (sellVolume == 0) return buyVolume >= IMBALANCE_RATIO; // treat 0 as 1 effectively
        if (buyVolume == 0) return sellVolume >= IMBALANCE_RATIO;
        return buyVolume >= IMBALANCE_RATIO * sellVolume
            || sellVolume >= IMBALANCE_RATIO * buyVolume;
    }
}
