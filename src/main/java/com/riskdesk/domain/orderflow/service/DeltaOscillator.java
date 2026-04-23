package com.riskdesk.domain.orderflow.service;

/**
 * Pure EMA-based delta oscillator (UC-OF-007).
 * <p>
 * Computes the difference between a fast EMA and a slow EMA of cumulative delta values.
 * Positive oscillator = bullish order flow bias, negative = bearish.
 * <p>
 * This is a stateful service — one instance per instrument/timeframe. No Spring, no I/O.
 *
 * <pre>
 * EMA formula: ema = value * multiplier + prevEma * (1 - multiplier)
 *   where multiplier = 2.0 / (period + 1)
 * Oscillator = EMA(fast) - EMA(slow)
 * </pre>
 */
public final class DeltaOscillator {

    private static final double DEFAULT_NEUTRAL_THRESHOLD = 0.5;

    private final int fastPeriod;
    private final int slowPeriod;
    private final double neutralThreshold;
    private final double fastMultiplier;
    private final double slowMultiplier;

    private double fastEma;
    private double slowEma;
    private boolean initialized;

    /**
     * Create an oscillator with default periods (fast=3, slow=10) and neutral threshold (0.5).
     */
    public DeltaOscillator() {
        this(3, 10, DEFAULT_NEUTRAL_THRESHOLD);
    }

    /**
     * Create an oscillator with custom periods and neutral threshold.
     *
     * @param fastPeriod       fast EMA period (must be >= 1)
     * @param slowPeriod       slow EMA period (must be > fastPeriod)
     * @param neutralThreshold absolute oscillator value below which bias is NEUTRAL
     */
    public DeltaOscillator(int fastPeriod, int slowPeriod, double neutralThreshold) {
        if (fastPeriod < 1) {
            throw new IllegalArgumentException("fastPeriod must be >= 1, got " + fastPeriod);
        }
        if (slowPeriod <= fastPeriod) {
            throw new IllegalArgumentException("slowPeriod (" + slowPeriod + ") must be > fastPeriod (" + fastPeriod + ")");
        }
        if (neutralThreshold < 0.0) {
            throw new IllegalArgumentException("neutralThreshold must be >= 0, got " + neutralThreshold);
        }

        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.neutralThreshold = neutralThreshold;
        this.fastMultiplier = 2.0 / (fastPeriod + 1);
        this.slowMultiplier = 2.0 / (slowPeriod + 1);
        this.fastEma = 0.0;
        this.slowEma = 0.0;
        this.initialized = false;
    }

    /**
     * Feed a new delta value and return the oscillator reading (fast EMA - slow EMA).
     * <p>
     * The first value seeds both EMAs directly. Subsequent values apply the standard EMA formula.
     *
     * @param currentDelta the current net delta value
     * @return oscillator value: positive = bullish flow, negative = bearish flow
     */
    public double compute(double currentDelta) {
        if (!initialized) {
            this.fastEma = currentDelta;
            this.slowEma = currentDelta;
            this.initialized = true;
        } else {
            this.fastEma = currentDelta * fastMultiplier + fastEma * (1.0 - fastMultiplier);
            this.slowEma = currentDelta * slowMultiplier + slowEma * (1.0 - slowMultiplier);
        }
        return fastEma - slowEma;
    }

    /**
     * Current directional bias based on the oscillator value.
     *
     * @return "BULLISH" if oscillator > threshold, "BEARISH" if < -threshold, "NEUTRAL" otherwise
     */
    public String bias() {
        double oscillator = fastEma - slowEma;
        if (oscillator > neutralThreshold) {
            return "BULLISH";
        } else if (oscillator < -neutralThreshold) {
            return "BEARISH";
        }
        return "NEUTRAL";
    }

    /**
     * Reset all internal state. The next {@link #compute} call will re-seed the EMAs.
     */
    public void reset() {
        this.fastEma = 0.0;
        this.slowEma = 0.0;
        this.initialized = false;
    }
}
