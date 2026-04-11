package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.FootprintBar;
import com.riskdesk.domain.orderflow.model.FootprintLevel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure domain service that accumulates classified trade ticks into footprint bars.
 * NOT a Spring bean — instantiated per instrument by the infrastructure adapter.
 *
 * <p>Each tick is rounded to the instrument's tick size and its volume is attributed
 * to either the buy (aggressive buyer at ask) or sell (aggressive seller at bid) side
 * at that price level.</p>
 *
 * <p>Thread safety: external callers must synchronize if used from multiple threads.</p>
 */
public class FootprintAggregator {

    private static final double IMBALANCE_RATIO = 3.0;

    private final Instrument instrument;
    private final double tickSize;

    // price -> {buyVol, sellVol}
    private final TreeMap<Double, long[]> currentBarLevels = new TreeMap<>();

    public FootprintAggregator(Instrument instrument, double tickSize) {
        if (tickSize <= 0) {
            throw new IllegalArgumentException("tickSize must be positive, got: " + tickSize);
        }
        this.instrument = instrument;
        this.tickSize = tickSize;
    }

    /**
     * Records a classified trade tick into the current bar.
     *
     * @param price          the trade price
     * @param size           the number of contracts
     * @param classification "BUY" or "SELL" (anything else is ignored)
     * @param timestamp      the tick timestamp (not stored, available for future extensions)
     */
    public void onTick(double price, long size, String classification, Instant timestamp) {
        if (size <= 0) return;
        if (!"BUY".equals(classification) && !"SELL".equals(classification)) return;

        double roundedPrice = roundToTickSize(price);
        long[] volumes = currentBarLevels.computeIfAbsent(roundedPrice, k -> new long[2]);

        if ("BUY".equals(classification)) {
            volumes[0] += size;
        } else {
            volumes[1] += size;
        }
    }

    /**
     * Creates an immutable snapshot of the current bar state.
     *
     * @param barTimestamp epoch seconds of the candle open
     * @param timeframe   the bar timeframe (e.g. "5m", "1m")
     * @return the footprint bar with computed POC and imbalance flags
     */
    public FootprintBar snapshot(long barTimestamp, String timeframe) {
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
            timeframe,
            barTimestamp,
            Map.copyOf(levels),
            pocPrice,
            totalBuy,
            totalSell,
            totalBuy - totalSell
        );
    }

    /**
     * Clears accumulated tick data for the next bar.
     */
    public void reset() {
        currentBarLevels.clear();
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

    private double roundToTickSize(double price) {
        return Math.round(price / tickSize) * tickSize;
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
