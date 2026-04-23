package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Aggregated order book depth metrics computed on-demand from the MutableOrderBook.
 * Lightweight immutable snapshot for consumption by detectors and WebSocket push.
 */
public record DepthMetrics(
    Instrument instrument,
    long totalBidSize,
    long totalAskSize,
    /** (totalBid - totalAsk) / (totalBid + totalAsk). Range: -1.0 to 1.0. */
    double depthImbalance,
    double bestBid,
    double bestAsk,
    double spread,
    double spreadTicks,
    /** Largest bid cluster above wall threshold, or null if none. */
    WallInfo bidWall,
    /** Largest ask cluster above wall threshold, or null if none. */
    WallInfo askWall,
    Instant timestamp
) {}
