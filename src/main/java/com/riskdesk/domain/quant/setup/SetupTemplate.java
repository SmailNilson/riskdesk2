package com.riskdesk.domain.quant.setup;

/**
 * Named setup template — each maps to a specific confluence pattern with
 * pre-defined R:R parameters (see {@code docs/analysis/SCALPING_DAYTRADING_FUSION.md}).
 */
public enum SetupTemplate {
    /** Template A — Day-trading reversal at HTF premium/discount zone (R:R 1:3). */
    A_DAY_REVERSAL(SetupStyle.DAY,  3.0),
    /** Template B — Scalp mean-reversion at VWAP (R:R 1:2). */
    B_SCALP_MR(SetupStyle.SCALP,    2.0),
    /** Template C — Naked POC fill after distribution cycle (R:R 1:2.5). */
    C_NAKED_POC(SetupStyle.DAY,     2.5),
    /** Template D — Multi-timeframe alignment momentum (R:R 1:2). */
    D_MTF_ALIGN(SetupStyle.DAY,     2.0),
    /** Template E — FVG sweep + rejection scalp (R:R 1:2). */
    E_FVG_SWEEP(SetupStyle.SCALP,   2.0),
    /** Fallback — no template matched. */
    UNKNOWN(SetupStyle.DAY,         1.5);

    public final SetupStyle defaultStyle;
    public final double targetRr;

    SetupTemplate(SetupStyle defaultStyle, double targetRr) {
        this.defaultStyle = defaultStyle;
        this.targetRr = targetRr;
    }
}
