package com.riskdesk.domain.analysis.model;

/**
 * One bullish or bearish factor surfaced in the UI list.
 *
 * @param polarity   BULLISH or BEARISH (NEUTRAL not allowed — it would be useless)
 * @param layer      "Structure" / "OrderFlow" / "Momentum" / "Macro"
 * @param description short human-readable text, e.g. "DIST x42 conf 100 (29m ago)"
 * @param strength   relative strength 0-100 — for sorting in the UI
 */
public record Factor(Polarity polarity, String layer, String description, double strength) {

    public enum Polarity { BULLISH, BEARISH }
}
