package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.orderflow.model.MomentumSignal.MomentumSide;

import java.time.Instant;
import java.util.Optional;

/**
 * Detects aggressive momentum bursts — the inverse of absorption.
 * <p>
 * Fires when delta, price movement, and volume align in the same direction:
 * big delta pushing price hard with elevated volume means no one is absorbing.
 * This fills the detection gap during active trend phases that absorption
 * (which requires price stability) cannot catch.
 * <p>
 * Score formula (mirror of absorption with price factor inverted):
 * <pre>
 *   score = (|delta| / deltaThreshold) * (priceMoveTicks / atr) * (volume / avgVolume)
 * </pre>
 * <p>
 * Stateless — each call is independent. No Spring, no I/O.
 */
public final class AggressiveMomentumDetector {

    public static final double DEFAULT_SCORE_THRESHOLD = 2.0;
    /** Price must move at least this fraction of ATR to qualify. */
    public static final double DEFAULT_MIN_PRICE_MOVE_FRACTION = 0.3;

    private final double scoreThreshold;
    private final double minPriceMoveFraction;

    public AggressiveMomentumDetector() {
        this(DEFAULT_SCORE_THRESHOLD, DEFAULT_MIN_PRICE_MOVE_FRACTION);
    }

    public AggressiveMomentumDetector(double scoreThreshold, double minPriceMoveFraction) {
        this.scoreThreshold = scoreThreshold;
        this.minPriceMoveFraction = minPriceMoveFraction;
    }

    /**
     * Evaluate whether the current snapshot constitutes an aggressive momentum burst.
     *
     * @param instrument      the futures instrument
     * @param delta           net aggressive delta (signed — positive = buyers)
     * @param priceMovePoints signed price move over the window (points, not ticks)
     * @param priceMoveTicks  absolute price move in ticks (normalizer = ATR ticks)
     * @param volume          total volume during the window
     * @param atr             current ATR (same units as priceMoveTicks — ticks)
     * @param deltaThreshold  baseline delta normalizer (e.g. 50)
     * @param avgVolume       average volume per window
     * @param timestamp       window end timestamp
     * @return momentum signal when score &gt; threshold AND directionally aligned
     */
    public Optional<MomentumSignal> evaluate(
            Instrument instrument,
            long delta,
            double priceMovePoints,
            double priceMoveTicks,
            long volume,
            double atr,
            double deltaThreshold,
            double avgVolume,
            Instant timestamp) {

        if (deltaThreshold <= 0.0 || atr <= 0.0 || avgVolume <= 0.0) {
            return Optional.empty();
        }
        if (delta == 0 || volume <= 0) return Optional.empty();

        // Directional alignment: delta sign must match price move sign
        double deltaSign = Math.signum((double) delta);
        double priceSign = Math.signum(priceMovePoints);
        if (priceSign == 0.0 || deltaSign != priceSign) {
            return Optional.empty();
        }

        double absMoveTicks = Math.abs(priceMoveTicks);
        // Move must be meaningful relative to ATR (not noise)
        if (absMoveTicks < atr * minPriceMoveFraction) {
            return Optional.empty();
        }

        double deltaFactor = Math.abs(delta) / deltaThreshold;
        double priceFactor = absMoveTicks / atr;          // INVERTED vs absorption
        double volumeFactor = volume / avgVolume;
        double score = deltaFactor * priceFactor * volumeFactor;

        if (score <= scoreThreshold) return Optional.empty();

        MomentumSide side = delta < 0 ? MomentumSide.BEARISH_MOMENTUM : MomentumSide.BULLISH_MOMENTUM;

        return Optional.of(new MomentumSignal(
            instrument,
            side,
            score,
            delta,
            absMoveTicks,
            priceMovePoints,
            volume,
            timestamp
        ));
    }
}
