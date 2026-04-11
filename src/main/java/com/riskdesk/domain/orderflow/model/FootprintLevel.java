package com.riskdesk.domain.orderflow.model;

/**
 * A single price level within a footprint bar, showing bid/ask volume split.
 * Used for order flow analysis: delta at each price level and imbalance detection.
 */
public record FootprintLevel(
    double price,
    long buyVolume,     // contracts at ask (aggressive buyers)
    long sellVolume,    // contracts at bid (aggressive sellers)
    long delta,         // buy - sell
    boolean imbalance   // true if one side dominates 3:1
) {}
