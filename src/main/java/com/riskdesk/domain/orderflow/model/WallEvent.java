package com.riskdesk.domain.orderflow.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Records when a wall (large order cluster) appears or disappears in the order book.
 * Used by the Spoofing Detector to identify ghost orders.
 */
public record WallEvent(
    Instrument instrument,
    WallSide side,
    double price,
    long size,
    Instant timestamp,
    WallEventType type
) {
    public enum WallSide { BID, ASK }
    public enum WallEventType { APPEARED, DISAPPEARED }
}
