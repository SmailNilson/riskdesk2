package com.riskdesk.domain.analysis.model;

/**
 * Frozen snapshot of order flow state at the decision timestamp.
 * <p>
 * Deltas / depth come from {@code TickDataPort} + {@code MarketDepthPort};
 * recent event lists come from the persisted detector tables.
 */
public record OrderFlowContext(
    long delta,                          // signed, current 5min window
    double buyRatioPct,
    String deltaTrend,                   // RISING / FALLING / FLAT
    boolean divergenceDetected,
    String divergenceType,               // BULLISH_DIVERGENCE / BEARISH_DIVERGENCE / null
    String source,                       // REAL_TICKS / CLV_ESTIMATED
    Long totalBidSize,
    Long totalAskSize,
    Double depthImbalance,               // -1..+1
    Double bestBid,
    Double bestAsk,
    Double spread
) {
}
