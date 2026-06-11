package com.riskdesk.domain.orderflow.model;

/**
 * A single price level within a footprint bar, showing bid/ask volume split.
 * Used for order flow analysis: delta at each price level and imbalance detection.
 *
 * <p>Diagonal imbalances follow the industry-standard bid/ask footprint convention:
 * aggressive buying at price P is compared against aggressive selling one bucket
 * <em>lower</em> (buyers lifting offers vs the sellers who hit bids a level below),
 * and aggressive selling at P against buying one bucket <em>higher</em>. A missing
 * neighbour bucket counts as zero volume; a minimum-volume filter on the larger cell
 * of the pair suppresses noise (e.g. 4-vs-1).</p>
 */
public record FootprintLevel(
    double price,
    long buyVolume,     // contracts at ask (aggressive buyers)
    long sellVolume,    // contracts at bid (aggressive sellers)
    long delta,         // buy - sell
    /**
     * Deprecated: same-price imbalance (buy vs sell at the SAME bucket, 3:1) — not the
     * professional footprint signal. Retained for backward compatibility with persisted
     * profile JSON; use {@link #diagonalBuyImbalance} / {@link #diagonalSellImbalance}.
     * ({@code @Deprecated} itself is forbidden on record components by the JLS.)
     */
    boolean imbalance,
    /** Diagonal buy imbalance: buyVolume(P) ≥ ratio × sellVolume(P − 1 bucket), volume-filtered. */
    boolean diagonalBuyImbalance,
    /** Diagonal sell imbalance: sellVolume(P) ≥ ratio × buyVolume(P + 1 bucket), volume-filtered. */
    boolean diagonalSellImbalance
) {
    /** Legacy 5-arg shape (pre-diagonal rows) — diagonal flags default to false. */
    public FootprintLevel(double price, long buyVolume, long sellVolume, long delta, boolean imbalance) {
        this(price, buyVolume, sellVolume, delta, imbalance, false, false);
    }
}
