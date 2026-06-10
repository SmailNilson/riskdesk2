package com.riskdesk.domain.orderflow.model;

/**
 * A constant-tick-count bar ("tick chart" bar): closes after exactly N classified
 * trades rather than after a fixed time window. Bar duration therefore stretches
 * in quiet markets and compresses in fast ones — the classic activity-normalized
 * view used for scalping.
 */
public record TickBar(
    String instrument,
    /** Bar size in ticks (trades), e.g. 200. */
    int ticksPerBar,
    /** Monotonically increasing per-instrument sequence — unique merge key. */
    long seq,
    /** Epoch seconds of the first trade in the bar. */
    long openTime,
    /** Epoch seconds of the last trade so far (== close trade when complete). */
    long closeTime,
    double open,
    double high,
    double low,
    double close,
    /** Total contracts traded in the bar. */
    long volume,
    long buyVolume,
    long sellVolume,
    /** buyVolume - sellVolume. */
    long delta,
    /** Trades accumulated so far (== ticksPerBar when complete). */
    int tickCount,
    /** True once the bar reached ticksPerBar trades and is finalized. */
    boolean complete
) {}
