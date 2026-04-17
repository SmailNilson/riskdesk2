package com.riskdesk.domain.engine.strategy.model;

import java.math.BigDecimal;

/**
 * Strategy-layer projection of an Order Block. A <b>subset</b> of
 * {@link com.riskdesk.domain.engine.playbook.model.SmcOrderBlock} — we deliberately
 * keep only the fields the strategy engine needs. This decouples the new engine from
 * the legacy playbook model and lets us evolve either independently.
 *
 * @param bullish true when the OB is bullish (demand). A LONG setup uses bullish OBs.
 * @param top     upper bound (always ≥ bottom)
 * @param bottom  lower bound
 * @param mid     midpoint of the OB (entry candidate in SBDR = equilibrium)
 * @param qualityScore 0..100 — from upstream OF enrichment. Null when unknown.
 */
public record OrderBlockZone(
    boolean bullish,
    BigDecimal top,
    BigDecimal bottom,
    BigDecimal mid,
    Double qualityScore
) {
    public OrderBlockZone {
        if (top == null || bottom == null) {
            throw new IllegalArgumentException("OrderBlockZone top/bottom must not be null");
        }
        if (top.compareTo(bottom) < 0) {
            throw new IllegalArgumentException("OrderBlockZone top must be >= bottom");
        }
    }

    public BigDecimal height() {
        return top.subtract(bottom);
    }

    public boolean contains(BigDecimal price) {
        if (price == null) return false;
        return price.compareTo(bottom) >= 0 && price.compareTo(top) <= 0;
    }
}
