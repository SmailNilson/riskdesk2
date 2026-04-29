package com.riskdesk.domain.quant.pattern;

/**
 * Output of {@link OrderFlowPatternDetector#detect}: which order-flow regime
 * is in play right now, the human-readable label/reason and the recommended
 * action for the trader.
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

    public static PatternAnalysis indeterminate(String reason) {
        return new PatternAnalysis(OrderFlowPattern.INDETERMINE, "Indéterminé", reason,
            Confidence.LOW, Action.WAIT);
    }
}
