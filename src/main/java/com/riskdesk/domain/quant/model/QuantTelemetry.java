package com.riskdesk.domain.quant.model;

import java.util.List;

/**
 * First-class structured telemetry for the LOB Microstructure dashboard.
 *
 * <p>Every value here is something the {@code GateEvaluator} already computes
 * while building the human-readable gate reasons — the record simply exposes
 * them on the wire so the frontend no longer has to regex-parse the French
 * {@code reason} strings (which broke on locale formatting, made LONG-side
 * gates dead code behind a {@code g1 || l1} short-circuit, and forced the UI
 * to fabricate values like a BEAR-dominance fallback).</p>
 *
 * <ul>
 *   <li><b>delta / buyPct</b> — {@code null} when the gates abstained because
 *       the tick feed was down; the matching {@code *Abstain} flag makes the
 *       outage explicit so the UI never renders a fake neutral value.</li>
 *   <li><b>deltaThreshold</b> — the real G3/L3 decision boundary magnitude
 *       (|±100|), not a frontend-invented band.</li>
 *   <li><b>absorption*</b> — the 3-minute absorption-event window: count of
 *       events scoring ≥ 8 ({@code absorptionN8}, unbounded), true dominant
 *       side ({@code BULL}/{@code BEAR}/{@code MIX}) and max score
 *       ({@code null} when no event in the window).</li>
 *   <li><b>ad*</b> — the most recent A/D event within the 10-minute window
 *       plus the dynamic G5/L5 thresholds and per-direction block verdicts.
 *       {@code adEventAgeSeconds} is the age of that event at scan time so a
 *       10-second detection is never displayed as a live "process" for
 *       25 minutes with no age indicator.</li>
 * </ul>
 *
 * @param delta               5-min rolling delta sampled at scan time ({@code null} on abstain)
 * @param deltaAbstain        {@code true} when G3/L3 abstained (delta feed down/stale)
 * @param deltaHistory        last scans' deltas (capped at 3, oldest first)
 * @param deltaThreshold      magnitude of the G3/L3 decision boundary — the per-instrument
 *                            RESOLVED value (e.g. 100 on MNQ, 40 on MCL), not a global constant
 * @param buyPct              buy ratio percentage 0–100 ({@code null} on abstain)
 * @param buyAbstain          {@code true} when G4/L4 abstained (delta feed down/stale)
 * @param bearishLimitPct     G4 limit — buy% below this favours SHORT (currently 48)
 * @param bullishLimitPct     L4 limit — buy% above this favours LONG (currently 52)
 * @param absorptionN8        absorption events scoring ≥ 8 in the 3-min window (unbounded)
 * @param absorptionDominance true dominant side: {@code BULL}, {@code BEAR} or {@code MIX}
 * @param absorptionMaxScore  max absorption score in the window ({@code null} when no event)
 * @param absorptionMinN8     G1/L1 minimum n8 for the gate to engage (currently 8)
 * @param adType              most recent A/D event type in the 10-min window ({@code DISTRIBUTION}/{@code ACCUMULATION}/{@code null})
 * @param adConfidence        RAW confidence of {@code adType} ({@code null} when no event)
 * @param adEffectiveConfidence age-decayed confidence the G5/L5 veto actually compared against
 *                            its tier: {@code conf × max(0, 1 - age/decaySeconds)} — a stale
 *                            event no longer vetoes at full strength ({@code null} when no event)
 * @param adDistThreshold     dynamic L5 DIST veto threshold for the current delta/buy% context
 * @param adAccuThreshold     dynamic G5 ACCU veto threshold for the current delta/buy% context
 * @param adLongBlocked       {@code true} when the L5 gate blocked the LONG track
 * @param adShortBlocked      {@code true} when the G5 gate blocked the SHORT track
 * @param adEventAgeSeconds   age of the A/D event driving the gate, in seconds ({@code null} when unknown)
 */
public record QuantTelemetry(
    Double delta,
    boolean deltaAbstain,
    List<Double> deltaHistory,
    double deltaThreshold,
    Double buyPct,
    boolean buyAbstain,
    double bearishLimitPct,
    double bullishLimitPct,
    int absorptionN8,
    String absorptionDominance,
    Double absorptionMaxScore,
    int absorptionMinN8,
    String adType,
    Integer adConfidence,
    Double adEffectiveConfidence,
    int adDistThreshold,
    int adAccuThreshold,
    boolean adLongBlocked,
    boolean adShortBlocked,
    Long adEventAgeSeconds
) {
    public QuantTelemetry {
        deltaHistory = deltaHistory == null ? List.of() : List.copyOf(deltaHistory);
    }
}
