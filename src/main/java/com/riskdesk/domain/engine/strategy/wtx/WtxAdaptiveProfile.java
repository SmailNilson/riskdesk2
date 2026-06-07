package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.domain.engine.indicators.MarketRegimeDetector;

import java.util.List;

/**
 * Data-derived "adapted profile" per (timeframe, regime) for the WTX strategy, and the gate for the
 * regime-conditional RIDE exit.
 *
 * <p>Backtest finding (MNQ, real engine, deployed config) — see {@code docs/WTX_ANALYSIS.md}:
 * <ul>
 *   <li><b>5m + TENDANCE → RIDE</b>: the tight 30/15 point trail clips fast 5m trend legs early; letting
 *       the position ride to the opposite WaveTrend cross lifts the 5m flux (+1679$ paper, qty1).</li>
 *   <li><b>everything else → TRAIL</b>: RANGING/CHOPPY (the bulk of the edge) and the slower 10m are best
 *       on the existing trailing stop — RIDE HURTS the 10m flux (gives back at the reversal).</li>
 * </ul>
 *
 * <p>{@link #recommend} is the informational badge surfaced in the UI next to the regime badge.
 * {@link #shouldRide} adds the config gate (enabled + instrument scope) and is what actually engages the
 * RIDE exit in {@code WtxStrategyService}. Pure functions — no Spring, no I/O.
 */
public final class WtxAdaptiveProfile {

    private WtxAdaptiveProfile() {}

    /** Let the position run to the opposite WT cross (no tight trailing). */
    public static final String RIDE = "RIDE";
    /** Manage the position with the trailing stop (default behaviour). */
    public static final String TRAIL = "TRAIL";

    private static boolean isTrending(String regime) {
        return MarketRegimeDetector.TRENDING_UP.equals(regime)
                || MarketRegimeDetector.TRENDING_DOWN.equals(regime);
    }

    /**
     * Recommended exit profile for a panel's (timeframe, regime). RIDE only for 5m in a TENDANCE regime;
     * TRAIL otherwise. Returns {@code null} when the regime is unknown (not enough history) so the UI can
     * hide the badge — mirrors the regime badge's null handling.
     */
    public static String recommend(String timeframe, String regime) {
        if (regime == null) {
            return null;
        }
        if (isTrending(regime) && "5m".equals(timeframe)) {
            return RIDE;
        }
        return TRAIL;
    }

    /**
     * Config-GATED recommendation for the UI badge — reflects what the engine will ACTUALLY do, not just
     * the data-derived ideal. Returns {@link #RIDE} only when {@link #shouldRide} is true (enabled + in
     * scope + 5m-trending), {@link #TRAIL} for any other known regime, and {@code null} when the regime is
     * unknown. Prevents the badge from showing RIDE on an out-of-scope instrument or when RIDE is disabled.
     */
    public static String recommendGated(boolean enabled, List<String> rideInstruments,
                                        String instrument, String timeframe, String regime) {
        if (regime == null) {
            return null;
        }
        return shouldRide(enabled, rideInstruments, instrument, timeframe, regime) ? RIDE : TRAIL;
    }

    /**
     * True when the regime-conditional RIDE exit should engage for a freshly-opened position, i.e. the
     * feature is enabled, the instrument is in scope, and the (timeframe, regime) recommends RIDE. An empty
     * / null instrument list applies RIDE to every instrument.
     */
    public static boolean shouldRide(boolean enabled, List<String> rideInstruments,
                                     String instrument, String timeframe, String regime) {
        if (!enabled) {
            return false;
        }
        if (!RIDE.equals(recommend(timeframe, regime))) {
            return false;
        }
        return rideInstruments == null || rideInstruments.isEmpty() || rideInstruments.contains(instrument);
    }
}
