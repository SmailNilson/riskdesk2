package com.riskdesk.domain.engine.strategy.model;

/**
 * Depth-of-market imbalance classification. STACKED_* mean multiple consecutive
 * price levels on the same side show above-average size; SPOOF means large resting
 * orders appeared and pulled inside a short window.
 */
public enum DomSignal {
    STACKED_BID,
    STACKED_ASK,
    SPOOF,
    NEUTRAL,
    UNAVAILABLE
}
