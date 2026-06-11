package com.riskdesk.domain.marketdata.model;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Aggregated order flow data from a rolling time window of classified trade ticks.
 * Used by the Mentor IA to evaluate real buying/selling pressure.
 *
 * <p>Source is either {@code REAL_TICKS} (from IBKR tick-by-tick AllLast feed,
 * classified via Lee-Ready rule) or {@code CLV_ESTIMATED} (from candle Close Location Value).</p>
 */
public record TickAggregation(
    Instrument instrument,
    long buyVolume,
    long sellVolume,
    long delta,                 // buyVolume - sellVolume
    long cumulativeDelta,       // session-anchored CVD: signed volume since RTH open (09:30 ET)
                                // when inside RTH, else since the Globex daily session start
                                // (17:00 ET) — see SessionCvd. NOT the window delta.
    double buyRatioPct,         // buyVolume / (buyVolume + sellVolume) * 100
    String deltaTrend,          // RISING, FALLING, FLAT
    boolean divergenceDetected, // price direction vs delta direction mismatch
    String divergenceType,      // BULLISH_DIVERGENCE, BEARISH_DIVERGENCE, or null
    Instant windowStart,
    Instant windowEnd,
    String source,              // REAL_TICKS or CLV_ESTIMATED
    double highPrice,           // highest trade price in window (NaN if no data)
    double lowPrice,            // lowest trade price in window (NaN if no data)
    double firstPrice,          // first trade price in window (NaN if no data) — signed-move baseline
    double lastPrice            // last trade price in window (NaN if no data) — signed-move endpoint
) {

    /** Delta trend: cumulative delta is increasing. */
    public static final String TREND_RISING = "RISING";
    /** Delta trend: cumulative delta is decreasing. */
    public static final String TREND_FALLING = "FALLING";
    /** Delta trend: cumulative delta is flat (no significant change). */
    public static final String TREND_FLAT = "FLAT";

    /** Source: real tick-by-tick data from IBKR AllLast feed, quote-classified (Lee-Ready). */
    public static final String SOURCE_REAL_TICKS = "REAL_TICKS";
    /**
     * Source: real tick-by-tick trades, but a meaningful share of the window was classified by
     * the trade-to-trade tick rule (uptick=BUY / downtick=SELL) because no fresh BBO/quote was
     * available. The volume is real; direction is less reliable than quote Lee-Ready, so strict
     * consumers must treat it at reduced ({@code CLV_ESTIMATED}, 0.5) confidence — never as REAL.
     */
    public static final String SOURCE_REAL_TICKS_TICKRULE = "REAL_TICKS_TICKRULE";
    /** Source: estimated from candle Close Location Value (CLV). */
    public static final String SOURCE_CLV_ESTIMATED = "CLV_ESTIMATED";

    /** Divergence: price rising but delta falling = sellers absorbing. */
    public static final String DIVERGENCE_BEARISH = "BEARISH_DIVERGENCE";
    /** Divergence: price falling but delta rising = buyers accumulating. */
    public static final String DIVERGENCE_BULLISH = "BULLISH_DIVERGENCE";
}
