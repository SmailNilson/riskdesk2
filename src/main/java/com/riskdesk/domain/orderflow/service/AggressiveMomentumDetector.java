package com.riskdesk.domain.orderflow.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.MomentumSignal;
import com.riskdesk.domain.orderflow.model.MomentumSignal.MomentumSide;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Detects aggressive momentum bursts — the inverse of absorption.
 * <p>
 * Fires when delta, price movement, and volume align in the same direction:
 * big delta pushing price hard with elevated volume means no one is absorbing.
 * This fills the detection gap during active trend phases that absorption
 * (which requires price stability) cannot catch.
 * <p>
 * <b>Score formula — sigmoid additive (bounded [0,1]):</b>
 * <pre>
 *   scoreDelta  = sigmoid(|delta| / deltaThreshold,  k=2)
 *   scorePrice  = sigmoid(priceMoveTicks / atr,      k=3)
 *   scoreVolume = sigmoid(volume / avgVolume,         k=2)
 *   score = 0.40 × scoreDelta + 0.35 × scorePrice + 0.25 × scoreVolume
 * </pre>
 * Where {@code sigmoid(x, k) = 1 / (1 + exp(-k × (x − 1)))}, centered at x=1 (= the
 * per-instrument baseline). This replaces the previous multiplicative formula that
 * exploded on MNQ during high-volatility events, causing per-bar spam.
 * <p>
 * <b>Anti-spam debounce:</b> requires at least 0.5 ATR of price movement since the last
 * fire in the same direction, plus a global rate cap of 2 fires per minute.
 * <p>
 * <b>Stateful — one instance per instrument.</b> Not thread-safe; expected to be called
 * from a single scheduler thread. No Spring, no I/O.
 */
public final class AggressiveMomentumDetector {

    /** New sigmoid-scale threshold: score ∈ [0,1], fires above 0.55. */
    public static final double DEFAULT_SCORE_THRESHOLD = 0.55;
    /** MNQ-tuned: price must move at least 40% of ATR to qualify (filters 1-2 tick noise). */
    public static final double DEFAULT_MIN_PRICE_MOVE_FRACTION = 0.4;
    /** Minimum ATR-distance from last fire in the same direction before re-firing. */
    public static final double ATR_DISTANCE_THRESHOLD = 0.5;
    /** Safety rate cap: at most this many fires per rolling 60-second window. */
    public static final int MAX_FIRES_PER_MINUTE = 2;

    private final double scoreThreshold;
    private final double minPriceMoveFraction;

    // Debounce state — per side, so bearish and bullish momentum are tracked independently
    private Double lastBearishFirePrice = null;
    private Double lastBullishFirePrice = null;
    /** Timestamps of recent fires (both sides) for the MAX_FIRES_PER_MINUTE rate cap. */
    private final Deque<Instant> recentFires = new ArrayDeque<>();

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
     * @param deltaThreshold  baseline delta normalizer (e.g. 100 RTH, 40 ETH)
     * @param avgVolume       average volume per window
     * @param currentPrice    current mid-price — used for ATR-distance debounce
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
            double currentPrice,
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

        // Sigmoid additive score — bounded [0, 1], no explosion on extreme values
        double scoreDelta  = sigmoid(Math.abs(delta) / deltaThreshold, 2.0);
        double scorePrice  = sigmoid(absMoveTicks / atr, 3.0);
        double scoreVolume = sigmoid(volume / avgVolume, 2.0);
        double score = 0.40 * scoreDelta + 0.35 * scorePrice + 0.25 * scoreVolume;

        if (score <= scoreThreshold) return Optional.empty();

        MomentumSide side = delta < 0 ? MomentumSide.BEARISH_MOMENTUM : MomentumSide.BULLISH_MOMENTUM;

        // Debounce: require ATR_DISTANCE of price movement since last fire in the same direction
        if (!Double.isNaN(currentPrice)) {
            Double lastFirePrice = (side == MomentumSide.BEARISH_MOMENTUM)
                ? lastBearishFirePrice : lastBullishFirePrice;
            if (lastFirePrice != null) {
                double distanceATR = Math.abs(currentPrice - lastFirePrice) / atr;
                if (distanceATR < ATR_DISTANCE_THRESHOLD) {
                    return Optional.empty();
                }
            }
        }

        // Rate cap: at most MAX_FIRES_PER_MINUTE fires across both sides in any 60s window
        Instant oneMinuteAgo = timestamp.minusSeconds(60);
        while (!recentFires.isEmpty() && recentFires.peekFirst().isBefore(oneMinuteAgo)) {
            recentFires.pollFirst();
        }
        if (recentFires.size() >= MAX_FIRES_PER_MINUTE) {
            return Optional.empty();
        }

        // Update debounce state before emitting
        if (side == MomentumSide.BEARISH_MOMENTUM) {
            lastBearishFirePrice = currentPrice;
        } else {
            lastBullishFirePrice = currentPrice;
        }
        recentFires.addLast(timestamp);

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

    /**
     * Logistic function centered at x=1 with steepness k.
     * Returns 0.5 when x=1 (= baseline), approaches 1 for large x, 0 for small x.
     */
    private static double sigmoid(double x, double k) {
        return 1.0 / (1.0 + Math.exp(-k * (x - 1.0)));
    }

    /** Reset debounce state (e.g. after session boundary). */
    public void reset() {
        lastBearishFirePrice = null;
        lastBullishFirePrice = null;
        recentFires.clear();
    }
}
