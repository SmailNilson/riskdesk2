package com.riskdesk.domain.quant.structure;

/**
 * A structural condition that vetoes a SHORT setup regardless of the quant
 * gates score (kill-switch). Each block is identified by a stable
 * {@link #code} (used by the frontend for styling/i18n) plus a free-form
 * {@link #evidence} string explaining what was observed.
 *
 * <p>See {@link StructuralFilterEvaluator} for the full block catalog.</p>
 */
public record StructuralBlock(String code, String evidence) {

    public StructuralBlock {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code required");
        }
        if (evidence == null) evidence = "";
    }

    /** ANY active bullish order block whose range contains the current price. */
    public static final String CODE_OB_BULL_FRESH       = "OB_BULL_FRESH";
    /** Strategy regime-context vote returned CHOPPY — no-trade BOTH directions. */
    public static final String CODE_REGIME_CHOPPY       = "REGIME_CHOPPY";
    /** ≥ 4/5 nested timeframes are BULLISH. */
    public static final String CODE_MTF_BULL            = "MTF_BULL";
    /** Strategy decision = NO_TRADE with a critical (non-maintenance) veto. */
    public static final String CODE_JAVA_NO_TRADE       = "JAVA_NO_TRADE_CRITICAL";
    /** CMF &gt; +0.15 — very strong structural buying flow. */
    public static final String CODE_CMF_VERY_BULL       = "CMF_VERY_BULL";

    // ── LONG mirrors (LONG-symmetry slice) ────────────────────────────────

    /** ANY active bearish order block whose range contains the current price (blocks LONG). */
    public static final String CODE_OB_BEAR_FRESH       = "OB_BEAR_FRESH";
    /** ≥ 4/5 nested timeframes are BEARISH (blocks LONG). */
    public static final String CODE_MTF_BEAR            = "MTF_BEAR";
    /** CMF &lt; -0.15 — very strong structural selling flow (blocks LONG). */
    public static final String CODE_CMF_VERY_BEAR       = "CMF_VERY_BEAR";
}
