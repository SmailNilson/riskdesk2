package com.riskdesk.domain.quant.pattern;

/**
 * Output of {@link OrderFlowPatternDetector#detect}: which order-flow regime
 * is in play right now, the human-readable label/reason and the recommended
 * action for the trader.
 *
 * <p>The {@link #action} field holds the recommendation from the SHORT trader
 * perspective (legacy default — the quant module was originally built for SHORT
 * setups). LONG callers must use {@link #actionFor(TradeBias)} to get the
 * mirror recommendation. The pattern {@link #type} itself is direction-agnostic
 * and identical for both readings.</p>
 */
public record PatternAnalysis(
    OrderFlowPattern type,
    String label,
    String reason,
    Confidence confidence,
    Action action
) {
    public enum Confidence { LOW, MEDIUM, HIGH }

    public enum Action {
        /** Setup confirmed — execute according to the displayed plan. */
        TRADE,
        /** Order flow not aligned with the setup direction — wait for confirmation. */
        WAIT,
        /** Counter-signal detected — discard the setup. */
        AVOID
    }

    /**
     * Trader perspective used by {@link #actionFor(TradeBias)} to flip
     * TRADE/AVOID recommendations between SHORT (legacy default) and LONG.
     */
    public enum TradeBias { LONG, SHORT }

    public static PatternAnalysis indeterminate(String reason) {
        return new PatternAnalysis(OrderFlowPattern.INDETERMINE, "Indéterminé", reason,
            Confidence.LOW, Action.WAIT);
    }

    /**
     * Returns the recommendation as seen by a trader looking at {@code bias}.
     *
     * <p>The SHORT view is the legacy default stored in {@link #action()}. The
     * LONG view is the symmetric mirror: TRADE↔AVOID, WAIT stays WAIT (low
     * confidence is direction-agnostic, so is the {@code INDETERMINE} regime).</p>
     *
     * <p>Symmetry table (current pattern → SHORT action / LONG action):
     * <ul>
     *   <li>{@code ABSORPTION_HAUSSIERE} → AVOID / TRADE (passive buying = bullish setup)</li>
     *   <li>{@code DISTRIBUTION_SILENCIEUSE} → TRADE / AVOID (silent selling = bearish setup)</li>
     *   <li>{@code VRAIE_VENTE} → TRADE / AVOID (clean directional sell)</li>
     *   <li>{@code VRAI_ACHAT} → AVOID / TRADE (clean directional buy)</li>
     *   <li>{@code INDETERMINE} or any single-scan LOW-confidence read → WAIT / WAIT</li>
     * </ul>
     */
    public Action actionFor(TradeBias bias) {
        if (bias == null || bias == TradeBias.SHORT) return action;
        return switch (action) {
            case TRADE -> Action.AVOID;
            case AVOID -> Action.TRADE;
            case WAIT  -> Action.WAIT;
        };
    }
}
