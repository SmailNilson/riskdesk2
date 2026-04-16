package com.riskdesk.domain.engine.playbook.model;

/**
 * Canonical unit for trade-level risk sizing across the playbook / agent pipeline.
 *
 * <p><b>Semantics.</b> A risk fraction is a dimensionless number in {@code [0, MAX]}
 * that represents the portion of account equity risked on a single trade:
 * <pre>
 *   0.01  → 1.0% of equity at risk
 *   0.005 → 0.5% of equity at risk
 *   0.0   → do not trade (fully size-capped)
 * </pre>
 *
 * <p><b>Why this class exists.</b> Previously the same number flowed through
 * {@code PlaybookPlan.riskPercent}, {@code RiskManagementService.sizePct},
 * {@code FinalVerdict.sizePercent} and {@code TradeDecision.sizePercent} — all
 * named "percent" but actually carrying a fraction. Readers frequently assumed
 * {@code 1.0 = 1%} (percent) vs. {@code 0.01 = 1%} (fraction) and either
 * multiplied by 100 twice, or not at all. {@code RiskFraction} centralizes the
 * contract so there is one documented place that converts to display percent.
 *
 * <p>This is a helper class, not a value type — the existing {@code double}
 * fields keep their names for backward compatibility. Call sites that surface
 * the value to humans (logs, Gemini narration, UI payloads) must route through
 * {@link #toPercent(double)} so the unit is explicit at the boundary.
 *
 * <p>Range validation uses {@link #MAX_FRACTION} (10%) as an upper bound. Any
 * fraction above this is a bug: playbook-generated plans never exceed 1% and
 * risk-gate cuts can only reduce, never raise.
 */
public final class RiskFraction {

    /** Absolute upper bound. Anything above this is a bug, not a valid plan. */
    public static final double MAX_FRACTION = 0.10;

    /** Mechanical plan baseline when R:R ≥ 2.0 (1% of equity). */
    public static final double FULL = 0.01;

    /** Mechanical plan baseline when R:R &lt; 2.0 (0.5% of equity). */
    public static final double HALF = 0.005;

    /** Fully size-capped — do not send orders. */
    public static final double ZERO = 0.0;

    private RiskFraction() {}

    /**
     * Validates that {@code fraction} is in the legal range {@code [0, MAX_FRACTION]}.
     *
     * @throws IllegalArgumentException if the value is negative, NaN, or above the cap
     */
    public static double requireValid(double fraction) {
        if (Double.isNaN(fraction) || fraction < 0.0 || fraction > MAX_FRACTION) {
            throw new IllegalArgumentException(
                "risk fraction must be in [0, " + MAX_FRACTION + "], got " + fraction);
        }
        return fraction;
    }

    /** Clamps {@code fraction} into {@code [0, MAX_FRACTION]}. Never throws. */
    public static double clamp(double fraction) {
        if (Double.isNaN(fraction) || fraction <= 0.0) return 0.0;
        return Math.min(fraction, MAX_FRACTION);
    }

    /**
     * Converts a risk fraction to its display percent. {@code 0.01 → 1.0}.
     * Use this at every log / UI / narration boundary so the unit is explicit.
     */
    public static double toPercent(double fraction) {
        return fraction * 100.0;
    }

    /**
     * Converts a display percent back to a fraction. {@code 1.0 → 0.01}.
     * Primarily for tests and when parsing external inputs that are labeled
     * with a "%".
     */
    public static double fromPercent(double percent) {
        return percent / 100.0;
    }
}
