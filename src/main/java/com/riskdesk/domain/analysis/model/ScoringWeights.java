package com.riskdesk.domain.analysis.model;

/**
 * Weighting of the three scoring layers used by {@code TriLayerScoringEngine}.
 * <p>
 * Default 50/30/20 mirrors the framework documented in the Mentor system prompt:
 * Structure dominates because it answers <i>where</i> we are, Order Flow tells
 * <i>who</i> is moving, Momentum tells <i>when</i>. The three weights MUST sum
 * to 1.0; the canonical constructor enforces this.
 *
 * @param structure  weight applied to the SMC / multi-timeframe layer
 * @param orderFlow  weight applied to the absorption / momentum / cycle layer
 * @param momentum   weight applied to the indicator (RSI/MACD/...) layer
 */
public record ScoringWeights(double structure, double orderFlow, double momentum) {

    private static final double TOLERANCE = 1e-6;

    public ScoringWeights {
        if (structure < 0 || orderFlow < 0 || momentum < 0) {
            throw new IllegalArgumentException("weights must be non-negative");
        }
        double sum = structure + orderFlow + momentum;
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new IllegalArgumentException("weights must sum to 1.0 (got " + sum + ")");
        }
    }

    public static ScoringWeights defaults() {
        return new ScoringWeights(0.50, 0.30, 0.20);
    }
}
