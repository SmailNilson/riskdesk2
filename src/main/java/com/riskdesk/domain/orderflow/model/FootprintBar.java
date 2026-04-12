package com.riskdesk.domain.orderflow.model;

import java.util.Map;

/**
 * Domain model for a single candle's footprint — the bid/ask volume profile
 * at each price level within the candle.
 *
 * <p>Key metrics:
 * <ul>
 *   <li>POC (Point of Control) — price level with highest total volume</li>
 *   <li>Total delta — net buy vs sell aggression across all levels</li>
 *   <li>Per-level imbalance — identifies where one side dominates 3:1</li>
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
    long totalDelta
) {}
