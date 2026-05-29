package com.riskdesk.domain.orderflow.perfectsetup;

/**
 * Tuning knobs for {@link PerfectSetupDetector}. Pure value object — the
 * application layer builds it from {@code riskdesk.perfect-setup.*} properties.
 *
 * @param armThreshold            minimum number of passing axes (0-6) to arm a setup
 * @param regimeMinConf           minimum distribution/accumulation confidence (0-100) for the REGIME axis
 * @param icebergMinScore         minimum iceberg score (0-100) for the ICEBERG axis
 * @param nearLevelTicks          how close (in ticks) an iceberg must sit to price to count as "near"
 * @param bbLow                   Bollinger %B at/below which price is at the lower band (LONG value)
 * @param bbHigh                  Bollinger %B at/above which price is at the upper band (SHORT value)
 * @param absorptionClimaxMinScore minimum max-absorption score for the ABSORPTION axis
 * @param flashReversalMinScore   minimum flash-crash reversal score for the LIQUIDITY_GRAB axis
 * @param minRR                   minimum reward-to-risk for the RISK_REWARD axis (also a hard arm gate)
 * @param slBufferAtrFraction     stop buffer beyond the structural level, as a fraction of ATR
 * @param armTtlSeconds           seconds an armed setup waits for entry before EXPIRING
 * @param cooldownSeconds         seconds after a terminal state before the detector may re-arm
 */
public record PerfectSetupThresholds(
    int armThreshold,
    int regimeMinConf,
    double icebergMinScore,
    int nearLevelTicks,
    double bbLow,
    double bbHigh,
    double absorptionClimaxMinScore,
    double flashReversalMinScore,
    double minRR,
    double slBufferAtrFraction,
    long armTtlSeconds,
    long cooldownSeconds
) {
    /** Total number of confluence axes. */
    public static final int MAX_SCORE = 6;

    public PerfectSetupThresholds {
        if (armThreshold < 1 || armThreshold > MAX_SCORE) {
            throw new IllegalArgumentException("armThreshold must be in [1, " + MAX_SCORE + "]");
        }
        if (minRR <= 0) throw new IllegalArgumentException("minRR must be > 0");
    }

    /** Sensible defaults — 4/6 balanced arming, R:R ≥ 2. */
    public static PerfectSetupThresholds defaults() {
        return new PerfectSetupThresholds(
            4,      // armThreshold
            70,     // regimeMinConf
            50.0,   // icebergMinScore
            40,     // nearLevelTicks
            0.25,   // bbLow
            0.75,   // bbHigh
            8.0,    // absorptionClimaxMinScore
            50.0,   // flashReversalMinScore
            2.0,    // minRR
            0.5,    // slBufferAtrFraction
            900L,   // armTtlSeconds (15 min)
            300L    // cooldownSeconds (5 min)
        );
    }
}
