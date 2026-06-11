package com.riskdesk.domain.quant.engine;

import java.util.Map;

/**
 * Framework-free calibration knobs for {@link GateEvaluator}. Bound from
 * {@code riskdesk.quant.veto.*} / {@code riskdesk.quant.gates.*} by the
 * infrastructure layer ({@code QuantGateProperties}) and passed in at
 * construction time so the domain stays Spring-free.
 *
 * <p>Recalibrated from the 2026-06 production audit + 90-day event study:</p>
 * <ul>
 *   <li><b>vetoBaseThreshold</b> — base tier of the G5/L5 A/D structural veto.
 *       Raised 50 → 60: the old detector confidence floor was 50, identical to
 *       the old base tier, so 100% of DISTRIBUTION events armed the veto by
 *       construction (tautology). Dynamic escalation tiers stay monotonic at
 *       {@code base+10} / {@code base+20} (60/70/80 with the default).</li>
 *   <li><b>vetoDecaySeconds</b> — linear age decay of the veto: effective
 *       confidence = {@code conf × max(0, 1 - age/decaySeconds)}. The detector
 *       cooldown (8 min) is shorter than the veto lookup window (10 min), so a
 *       10–20 s detection used to drive a full-strength veto for 10+ minutes
 *       with no decay, re-arming before it could ever expire on an instrument
 *       firing routinely (MNQ fires several times/hour).</li>
 *   <li><b>deltaThresholds</b> — per-instrument G3/L3 delta gate magnitude.
 *       The historical ±100 constant was lifted from an MNQ-specific Python
 *       monitor; MCL trades far less volume so ±100 made its gate nearly
 *       always red. Unlisted instruments use {@code defaultDeltaThreshold}.</li>
 *   <li><b>bearishBuyPct / bullishBuyPct</b> — G4/L4 buy-ratio bands
 *       (global, configurable; 48/52 by default).</li>
 * </ul>
 */
public record QuantGateConfig(
    double vetoBaseThreshold,
    long vetoDecaySeconds,
    Map<String, Double> deltaThresholds,
    double defaultDeltaThreshold,
    double bearishBuyPct,
    double bullishBuyPct
) {
    public static final double DEFAULT_VETO_BASE_THRESHOLD = 60.0;
    public static final long   DEFAULT_VETO_DECAY_SECONDS  = 600L;
    public static final double DEFAULT_DELTA_THRESHOLD     = 100.0;
    public static final double DEFAULT_BEARISH_BUY_PCT     = 48.0;
    public static final double DEFAULT_BULLISH_BUY_PCT     = 52.0;

    public QuantGateConfig {
        if (vetoDecaySeconds <= 0) throw new IllegalArgumentException("vetoDecaySeconds > 0 required");
        if (defaultDeltaThreshold <= 0) throw new IllegalArgumentException("defaultDeltaThreshold > 0 required");
        deltaThresholds = deltaThresholds == null ? Map.of() : Map.copyOf(deltaThresholds);
    }

    /** Production defaults — also what the no-arg {@link GateEvaluator} constructor uses. */
    public static QuantGateConfig defaults() {
        return new QuantGateConfig(
            DEFAULT_VETO_BASE_THRESHOLD,
            DEFAULT_VETO_DECAY_SECONDS,
            Map.of("MNQ", 100.0, "MCL", 40.0),
            DEFAULT_DELTA_THRESHOLD,
            DEFAULT_BEARISH_BUY_PCT,
            DEFAULT_BULLISH_BUY_PCT
        );
    }

    /** Resolved G3/L3 delta gate magnitude for the instrument (falls back to the default). */
    public double deltaThresholdFor(String instrument) {
        Double v = deltaThresholds.get(instrument);
        return v != null ? v : defaultDeltaThreshold;
    }
}
