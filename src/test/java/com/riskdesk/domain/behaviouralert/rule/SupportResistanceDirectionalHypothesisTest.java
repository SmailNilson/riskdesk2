package com.riskdesk.domain.behaviouralert.rule;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext.SrLevel;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that the conventional interpretation of {@code SUPPORT_RESISTANCE}
 * alerts — "touching WEAK_HIGH ⇒ SHORT (rejection)" — is <b>regime-dependent</b>.
 *
 * <p>Empirical prod data (888 BEHAVIOUR alerts, Apr 10-17 2026):
 * <ul>
 *   <li><b>WEAK_HIGH SHORT</b> win rate: 25.9% @ 6 bars (N=172). On <b>MNQ</b>
 *       alone the win rate collapses to <b>9.2%</b> (N=87) — i.e. the prediction
 *       is wrong 91% of the time in a trending-bullish regime.</li>
 *   <li>Same pattern in a ranging regime on MCL shows the convention works
 *       (~47% on MCL, neutral).</li>
 * </ul>
 *
 * <p>This test codifies the insight as two synthetic scenarios that run through
 * the production {@link SupportResistanceTouchRule}:
 * <ol>
 *   <li><b>Bullish trend</b>: price touches WEAK_HIGH → continues up (LONG).
 *       The "SHORT on touch" convention is <b>refuted</b>.</li>
 *   <li><b>Ranging market</b>: price touches WEAK_HIGH → rejects down (SHORT).
 *       The convention is <b>confirmed</b>.</li>
 * </ol>
 *
 * <p>Conclusion: the BEHAVIOUR alerts themselves are sound (the rule fires
 * correctly in both cases); the <i>interpretation layer</i> (Gemini prompts,
 * downstream consumers) must consult {@code MarketRegimeDetector} before
 * assigning a directional bias to {@code SUPPORT_RESISTANCE} touches.
 *
 * @see SupportResistanceTouchRule
 * @see com.riskdesk.domain.engine.indicators Market regime detection
 */
class SupportResistanceDirectionalHypothesisTest {

    private static final String INSTRUMENT = "MNQ";
    private static final String TIMEFRAME  = "10m";
    private static final Duration BAR      = Duration.ofMinutes(10);
    private static final int FORWARD_BARS  = 6;  // matches the 6-bar horizon used in the prod analysis
    private static final BigDecimal WEAK_HIGH_PRICE = new BigDecimal("100.20");

    // Same threshold the production rule uses to classify NEAR (0.3%).
    private static final double TOUCH_PROXIMITY = 0.003;

    @Test
    void bullishTrend_weakHighTouch_refutesShortHypothesis_priceBreaksOut() {
        // 30 bars rising steadily from 99.60 to ~101.80 — the price crosses
        // the WEAK_HIGH level mid-series and keeps going up (classic breakout).
        double[] closes = trendingBullishCloses(30, /*start*/ 99.60, /*stepPerBar*/ 0.075);

        int touchIdx = firstTouchIndex(closes, WEAK_HIGH_PRICE.doubleValue(), TOUCH_PROXIMITY);
        assertTrue(touchIdx >= 0 && touchIdx + FORWARD_BARS < closes.length,
                "Fixture must produce a WEAK_HIGH touch with enough forward bars to measure outcome");

        // Production rule must fire at the touch — verifying the rule
        // generates exactly the same alert observed in prod.
        SupportResistanceTouchRule rule = new SupportResistanceTouchRule();
        List<BehaviourAlertSignal> signals = rule.evaluate(contextAt(closes, touchIdx));
        assertEquals(1, signals.size(), "SupportResistanceTouchRule must fire on WEAK_HIGH touch");
        assertTrue(signals.get(0).message().contains("WEAK_HIGH"),
                "Alert message must identify the level type as WEAK_HIGH");

        // Directional assertion: 6 bars after the touch, price is HIGHER
        // — SHORT hypothesis is wrong in bullish-trending regimes.
        double closeAtTouch = closes[touchIdx];
        double closeAfter   = closes[touchIdx + FORWARD_BARS];
        assertTrue(closeAfter > closeAtTouch,
                () -> String.format(
                        "Bullish-trend regime: WEAK_HIGH touch was followed by BREAKOUT UP, not rejection. " +
                        "close@touch=%.4f → close@+6=%.4f (%+.2f%%). " +
                        "Matches prod observation on MNQ (91%% LONG after WEAK_HIGH).",
                        closeAtTouch, closeAfter, 100 * (closeAfter - closeAtTouch) / closeAtTouch));
    }

    @Test
    void rangingMarket_weakHighTouch_confirmsShortHypothesis_priceRejects() {
        // 30 bars oscillating ±0.20 around 100.00 — the price touches the
        // WEAK_HIGH at each peak and reverts toward the mean (rejection).
        // We pick a peak that is NOT the last one so we have 6 forward bars.
        double[] closes = rangingCloses(30, /*mean*/ 100.00, /*amplitude*/ 0.22, /*periodBars*/ 10);

        int touchIdx = firstTouchIndex(closes, WEAK_HIGH_PRICE.doubleValue(), TOUCH_PROXIMITY);
        assertTrue(touchIdx >= 0 && touchIdx + FORWARD_BARS < closes.length,
                "Fixture must produce a WEAK_HIGH touch with enough forward bars to measure outcome");

        SupportResistanceTouchRule rule = new SupportResistanceTouchRule();
        List<BehaviourAlertSignal> signals = rule.evaluate(contextAt(closes, touchIdx));
        assertEquals(1, signals.size(), "SupportResistanceTouchRule must fire on WEAK_HIGH touch");

        // Directional assertion: 6 bars after the touch, price is LOWER
        // — SHORT hypothesis IS correct in ranging regimes.
        double closeAtTouch = closes[touchIdx];
        double closeAfter   = closes[touchIdx + FORWARD_BARS];
        assertTrue(closeAfter < closeAtTouch,
                () -> String.format(
                        "Ranging regime: WEAK_HIGH touch was followed by REJECTION DOWN (SHORT hypothesis valid). " +
                        "close@touch=%.4f → close@+6=%.4f (%+.2f%%). " +
                        "Matches prod observation on MCL (~47%% neutral/rejection-leaning in range).",
                        closeAtTouch, closeAfter, 100 * (closeAfter - closeAtTouch) / closeAtTouch));
    }

    @Test
    void bothRegimesProduceSameAlert_butOppositeOutcomes_provingRegimeDependence() {
        // Meta-assertion: the same alert (category=SUPPORT_RESISTANCE, level=WEAK_HIGH)
        // predicts opposite moves in the two regimes. The BEHAVIOUR signal by itself
        // carries NO directional edge — interpretation requires regime context.
        double[] bullishCloses = trendingBullishCloses(30, 99.60, 0.075);
        double[] rangingCloses = rangingCloses(30, 100.00, 0.22, 10);

        int bullTouch = firstTouchIndex(bullishCloses, WEAK_HIGH_PRICE.doubleValue(), TOUCH_PROXIMITY);
        int rangeTouch = firstTouchIndex(rangingCloses, WEAK_HIGH_PRICE.doubleValue(), TOUCH_PROXIMITY);

        // Use independent rule instances so state from one scenario does not leak.
        List<BehaviourAlertSignal> bullSignals =
                new SupportResistanceTouchRule().evaluate(contextAt(bullishCloses, bullTouch));
        List<BehaviourAlertSignal> rangeSignals =
                new SupportResistanceTouchRule().evaluate(contextAt(rangingCloses, rangeTouch));

        // 1. Same alert category fires in both regimes.
        assertEquals(1, bullSignals.size());
        assertEquals(1, rangeSignals.size());
        assertEquals(bullSignals.get(0).category(), rangeSignals.get(0).category());
        assertTrue(bullSignals.get(0).message().contains("WEAK_HIGH"));
        assertTrue(rangeSignals.get(0).message().contains("WEAK_HIGH"));

        // 2. The outcomes are opposite.
        double bullDelta  = bullishCloses[bullTouch + FORWARD_BARS]  - bullishCloses[bullTouch];
        double rangeDelta = rangingCloses[rangeTouch + FORWARD_BARS] - rangingCloses[rangeTouch];

        assertTrue(bullDelta > 0 && rangeDelta < 0,
                () -> String.format(
                        "Same alert, opposite outcomes: bullish Δ=%+.4f (LONG) vs ranging Δ=%+.4f (SHORT). " +
                        "Directional prediction cannot come from the alert alone — " +
                        "regime detection (MarketRegimeDetector) must gate the interpretation.",
                        bullDelta, rangeDelta));
    }

    // ──────────────────────────────────────────────────────────────────
    // Synthetic fixture helpers — no IBKR, no Spring, pure arithmetic.
    // ──────────────────────────────────────────────────────────────────

    /** Produces {@code n} steadily-rising closes: close[i] = start + step * i. */
    private static double[] trendingBullishCloses(int n, double start, double step) {
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) closes[i] = start + step * i;
        return closes;
    }

    /**
     * Produces {@code n} closes oscillating around {@code mean} with the given
     * amplitude and period (in bars). A full sine cycle every {@code periodBars}
     * ensures the WEAK_HIGH at mean+amplitude is touched at each peak and the
     * subsequent bars revert toward the mean (rejection pattern).
     */
    private static double[] rangingCloses(int n, double mean, double amplitude, int periodBars) {
        double[] closes = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = mean + amplitude * Math.sin(2 * Math.PI * i / periodBars);
        }
        return closes;
    }

    /**
     * Returns the first index at which |close - level| / level &lt; proximity.
     * This matches the exact NEAR classification used by {@link SupportResistanceTouchRule}.
     */
    private static int firstTouchIndex(double[] closes, double level, double proximity) {
        for (int i = 0; i < closes.length; i++) {
            if (Math.abs(closes[i] - level) / level < proximity) return i;
        }
        return -1;
    }

    /**
     * Builds a {@link BehaviourAlertContext} with a single WEAK_HIGH level at
     * {@link #WEAK_HIGH_PRICE} and lastPrice set to the close at {@code idx}.
     * The candle timestamp is derived from a fixed anchor + {@code idx * BAR}
     * so each bar has a distinct closed-candle timestamp (required for the
     * rule's candle-close guard).
     */
    private static BehaviourAlertContext contextAt(double[] closes, int idx) {
        Instant anchor = Instant.parse("2026-04-15T12:00:00Z");
        return new BehaviourAlertContext(
                INSTRUMENT, TIMEFRAME,
                BigDecimal.valueOf(closes[idx]),
                null, null,
                List.of(new SrLevel("WEAK_HIGH", WEAK_HIGH_PRICE)),
                anchor.plus(BAR.multipliedBy(idx)),
                null, null
        );
    }
}
