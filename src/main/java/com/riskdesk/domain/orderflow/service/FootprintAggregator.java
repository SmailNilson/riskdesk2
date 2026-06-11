package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.model.FootprintLevel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>Prices are binned into buckets of {@code bucketSize} (e.g. 2.00 points for MNQ,
 * 0.05 for MCL — typically a multiple of the instrument's tick size). A trade is
 * attributed to the bucket whose lower bound it falls into: bucket = floor(price /
 * bucketSize) * bucketSize.</p>
 *
 * <p>Closed-bar snapshots carry diagonal imbalance flags, stacked-imbalance zones and
 * unfinished-auction flags computed by {@link FootprintImbalanceCalculator}.</p>
 *
 * <p>Thread safety: external callers must synchronize if used from multiple threads.</p>
 */
public class FootprintAggregator {

    private static final double DEFAULT_IMBALANCE_RATIO = 3.0;
    private static final long DEFAULT_MIN_CELL_VOLUME = 20;

    private final Instrument instrument;
    private final double bucketSize;
    private final int barSeconds;
    private final String timeframeLabel;
    /** Diagonal dominance ratio (3.0 = 300%). */
    private final double imbalanceRatio;
    /** Minimum contracts on the larger cell of the diagonal pair for it to flag. */
    private final long minCellVolume;

    // bucket lower bound -> {buyVol, sellVol}
    private final TreeMap<Double, long[]> currentBarLevels = new TreeMap<>();
    /** Epoch seconds of the current bar's open, aligned to barSeconds. -1 = no bar yet. */
    private long currentBarOpen = -1;

    public FootprintAggregator(Instrument instrument, double bucketSize, int barSeconds) {
        this(instrument, bucketSize, barSeconds, DEFAULT_IMBALANCE_RATIO, DEFAULT_MIN_CELL_VOLUME);
    }

    public FootprintAggregator(Instrument instrument, double bucketSize, int barSeconds,
                               double imbalanceRatio, long minCellVolume) {
        if (bucketSize <= 0) {
            throw new IllegalArgumentException("bucketSize must be positive, got: " + bucketSize);
        }
        if (barSeconds <= 0) {
            throw new IllegalArgumentException("barSeconds must be positive, got: " + barSeconds);
        }
        if (imbalanceRatio <= 1) {
            throw new IllegalArgumentException("imbalanceRatio must be > 1, got: " + imbalanceRatio);
        }
        this.instrument = instrument;
        this.bucketSize = bucketSize;
        this.barSeconds = barSeconds;
        this.imbalanceRatio = imbalanceRatio;
        this.minCellVolume = Math.max(0, minCellVolume);
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
        List<FootprintLevel> enriched = FootprintImbalanceCalculator.computeLevels(
            currentBarLevels, bucketSize, imbalanceRatio, minCellVolume);

        Map<Double, FootprintLevel> levels = new LinkedHashMap<>();
        long totalBuy = 0;
        long totalSell = 0;
        double pocPrice = 0;
        long pocVolume = 0;

        for (FootprintLevel level : enriched) {
            levels.put(level.price(), level);
            totalBuy += level.buyVolume();
            totalSell += level.sellVolume();

            long totalVol = level.buyVolume() + level.sellVolume();
            if (totalVol > pocVolume) {
                pocVolume = totalVol;
                pocPrice = level.price();
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
            totalBuy - totalSell,
            FootprintImbalanceCalculator.stackedZones(enriched, bucketSize, true),
            FootprintImbalanceCalculator.stackedZones(enriched, bucketSize, false),
            FootprintImbalanceCalculator.unfinishedHigh(enriched),
            FootprintImbalanceCalculator.unfinishedLow(enriched)
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
}
