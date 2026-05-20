package com.riskdesk.domain.quant.pattern;

/**
 * Deterministic classification of the current order-flow regime, derived from
 * the relationship between the cumulative delta and the live price action over
 * the last 3 scans.
 *
 * <ul>
 *   <li>{@link #ABSORPTION_HAUSSIERE} — sustained negative delta but price holds or
 *       rises: passive buyers absorbing aggressive sells (bullish setup).</li>
 *   <li>{@link #DISTRIBUTION_SILENCIEUSE} — sustained positive delta but price drifts
 *       lower: smart money distributing into retail buying (bearish setup).</li>
 *   <li>{@link #VRAIE_VENTE} — negative delta with price following down: clean
 *       directional sell.</li>
 *   <li>{@link #VRAI_ACHAT} — positive delta with price following up: clean
 *       directional buy.</li>
 *   <li>{@link #INDETERMINE} — not enough data or signals do not align.</li>
 * </ul>
 */
public enum OrderFlowPattern {
    ABSORPTION_HAUSSIERE,
    DISTRIBUTION_SILENCIEUSE,
    VRAIE_VENTE,
    VRAI_ACHAT,
    INDETERMINE
}
