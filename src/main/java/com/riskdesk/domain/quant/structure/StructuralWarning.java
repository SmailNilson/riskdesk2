package com.riskdesk.domain.quant.structure;

/**
 * A non-blocking structural condition that nudges the quant score down by
 * {@link #scoreModifier} (always ≤ 0). Warnings are surfaced to the trader so
 * they can size down or skip a marginal setup, but do not by themselves veto
 * the trade.
 *
 * <p>See {@link StructuralFilterEvaluator} for the full warning catalog.</p>
 */
public record StructuralWarning(String code, String evidence, int scoreModifier) {

    public StructuralWarning {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code required");
        }
        if (evidence == null) evidence = "";
        if (scoreModifier > 0) {
            throw new IllegalArgumentException("scoreModifier must be ≤ 0, got " + scoreModifier);
        }
    }

    /** Price below VWAP lower band by &gt; 1σ — mean-reversion risk. */
    public static final String CODE_VWAP_FAR            = "VWAP_FAR";
    /** Bollinger %B &lt; 0.15 — oversold-bounce risk. */
    public static final String CODE_BB_LOWER            = "BB_LOWER";
    /** CMF in (0.05, 0.15] — accumulation flow, not yet "very bull". */
    public static final String CODE_CMF_POSITIVE        = "CMF_POSITIVE";
    /** Price in DISCOUNT zone for SHORT (or PREMIUM for LONG). */
    public static final String CODE_PRICE_IN_DISCOUNT   = "PRICE_IN_DISCOUNT";
    /** Swing bias is BULLISH — SHORT against the swing. */
    public static final String CODE_SWING_BULL          = "SWING_BULL";
    /** Equal-lows liquidity pool within 15pts of the price (touchCount ≥ 2). */
    public static final String CODE_EQUAL_LOWS_NEAR     = "EQUAL_LOWS_NEAR";
    /** Java decision=NO_TRADE only because of maintenance-window — informational. */
    public static final String CODE_JAVA_MAINTENANCE    = "JAVA_MAINTENANCE";
    /** OB_BULL block was demoted by an override (recent CHoCH bear or strong VRAIE_VENTE). */
    public static final String CODE_OB_BULL_OVERRIDDEN  = "OB_BULL_OVERRIDDEN";
}
