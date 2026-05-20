package com.riskdesk.domain.quant.setup;

/**
 * Per-instrument weight configuration used by the scoring pipeline.
 * Default values represent equal weighting across the 5 signal dimensions.
 *
 * @param regimeWeight    importance of market-regime signal (TRENDING/RANGING)
 * @param htfBiasWeight   importance of higher-timeframe structural bias
 * @param deltaWeight     importance of order-flow delta direction
 * @param patternWeight   importance of detected order-flow pattern
 * @param sessionWeight   importance of session / kill-zone alignment
 */
public record WeightConfiguration(
    double regimeWeight,
    double htfBiasWeight,
    double deltaWeight,
    double patternWeight,
    double sessionWeight
) {
    public static final WeightConfiguration DEFAULT =
        new WeightConfiguration(0.20, 0.25, 0.25, 0.15, 0.15);

    /** Returns normalised weights that sum to 1.0 (guards against misconfigured DB rows). */
    public WeightConfiguration normalised() {
        double sum = regimeWeight + htfBiasWeight + deltaWeight + patternWeight + sessionWeight;
        if (sum <= 0) return DEFAULT;
        return new WeightConfiguration(
            regimeWeight / sum,
            htfBiasWeight / sum,
            deltaWeight / sum,
            patternWeight / sum,
            sessionWeight / sum
        );
    }
}
