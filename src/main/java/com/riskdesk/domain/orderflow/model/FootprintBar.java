package com.riskdesk.domain.orderflow.model;

import java.util.List;
import java.util.Map;

/**
 * Domain model for a single candle's footprint — the bid/ask volume profile
 * at each price level within the candle.
 *
 * <p>Key metrics:
 * <ul>
 *   <li>POC (Point of Control) — price level with highest total volume</li>
 *   <li>Total delta — net buy vs sell aggression across all levels</li>
 *   <li>Per-level diagonal imbalances — the professional bid/ask footprint signal
 *       (see {@link FootprintLevel})</li>
 *   <li>Stacked imbalance zones — ≥ 3 consecutive buckets flagged on the same side</li>
 *   <li>Unfinished auction — both sides traded at the bar's extreme (the auction did
 *       not finish; price often revisits the level)</li>
 * </ul>
 */
public record FootprintBar(
    String instrument,
    String timeframe,
    long barTimestamp,        // epoch seconds of candle open
    Map<Double, FootprintLevel> levels,  // price -> bid/ask volume
    double pocPrice,          // price level with highest total volume
    long totalBuyVolume,
    long totalSellVolume,
    long totalDelta,
    List<ImbalanceZone> stackedBuyZones,   // ≥3 consecutive diagonal buy imbalances
    List<ImbalanceZone> stackedSellZones,  // ≥3 consecutive diagonal sell imbalances
    boolean unfinishedHigh,   // top bucket traded on both sides — unfinished auction up
    boolean unfinishedLow     // bottom bucket traded on both sides — unfinished auction down
) {
    /** Legacy 8-arg shape — no stacked zones, no unfinished-auction flags. */
    public FootprintBar(String instrument, String timeframe, long barTimestamp,
                        Map<Double, FootprintLevel> levels, double pocPrice,
                        long totalBuyVolume, long totalSellVolume, long totalDelta) {
        this(instrument, timeframe, barTimestamp, levels, pocPrice,
             totalBuyVolume, totalSellVolume, totalDelta,
             List.of(), List.of(), false, false);
    }
}
