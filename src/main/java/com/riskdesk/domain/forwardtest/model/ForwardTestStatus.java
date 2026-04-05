package com.riskdesk.domain.forwardtest.model;

/**
 * Lifecycle states of a forward-test position.
 *
 * <pre>
 *   PENDING_LEG1 ──→ LEG1_FILLED ──→ FULLY_FILLED ──→ WIN
 *        │                │                │          ──→ LOSS
 *        │                │                │
 *        ├──→ MISSED      ├──→ WIN         ├──→ EXPIRED
 *        ├──→ EXPIRED     ├──→ LOSS        └──→ CANCELLED
 *        └──→ CANCELLED   └──→ EXPIRED
 * </pre>
 */
public enum ForwardTestStatus {

    /** Waiting for Leg 1 entry fill (limit order at entry_standard). */
    PENDING_LEG1,

    /** Leg 1 filled, waiting for Leg 2 fill or resolution. */
    LEG1_FILLED,

    /** Both legs filled, position fully sized. */
    FULLY_FILLED,

    /** Take-profit hit — winning trade. */
    WIN,

    /** Stop-loss hit — losing trade. */
    LOSS,

    /** TP reached before entry was touched — trade never entered. */
    MISSED,

    /** TTL deadline reached without entry or while still active. */
    EXPIRED,

    /** Manually cancelled or system-cancelled (e.g. duplicate instrument/direction). */
    CANCELLED;

    public boolean isTerminal() {
        return this == WIN || this == LOSS || this == MISSED || this == EXPIRED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == LEG1_FILLED || this == FULLY_FILLED;
    }

    public boolean isPending() {
        return this == PENDING_LEG1;
    }
}
