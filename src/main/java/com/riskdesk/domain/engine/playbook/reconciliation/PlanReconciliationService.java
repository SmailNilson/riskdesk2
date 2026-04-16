package com.riskdesk.domain.engine.playbook.reconciliation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares the mechanical plan (produced by {@code MechanicalPlanCalculator}, pure rules)
 * with the Gemini-proposed plan (AI) and flags meaningful divergences.
 *
 * <h2>Why</h2>
 * The audit identified a double source-of-truth: mechanical SL uses {@code 0.3×ATR}
 * while the Gemini prompt uses {@code 1.5–2×ATR}; mechanical R:R is ≥2.0, Gemini ≥1.5.
 * A trade may be marked ELIGIBLE by Gemini but rejected by the checklist, or vice-versa,
 * with no visible warning. This service makes the disagreement explicit so callers can
 * log, alert, or veto.
 *
 * <h2>Divergence thresholds (configurable at construction)</h2>
 * <ul>
 *   <li>{@code slThresholdAtr=1.5} — SL gap ≥ 1.5×ATR means the two plans protect
 *   fundamentally different invalidation levels</li>
 *   <li>{@code rrThreshold=0.5} — R:R gap ≥ 0.5 means materially different expectations
 *   of the upside</li>
 *   <li>{@code entryThresholdAtr=0.75} — Entry gap ≥ 0.75×ATR is beyond the slippage band</li>
 * </ul>
 *
 * <p>All thresholds are expressed in ATR multiples so the service works uniformly
 * across 5m/10m/1H/4H timeframes and across instruments (MNQ tick 0.25 vs MCL tick 0.01).
 *
 * <p>Pure domain: no Spring, no persistence, no logging side-effects.
 */
public final class PlanReconciliationService {

    public static final double DEFAULT_SL_THRESHOLD_ATR = 1.5;
    public static final double DEFAULT_RR_THRESHOLD = 0.5;
    public static final double DEFAULT_ENTRY_THRESHOLD_ATR = 0.75;

    private final BigDecimal slThresholdAtr;
    private final BigDecimal rrThreshold;
    private final BigDecimal entryThresholdAtr;

    public PlanReconciliationService() {
        this(DEFAULT_SL_THRESHOLD_ATR, DEFAULT_RR_THRESHOLD, DEFAULT_ENTRY_THRESHOLD_ATR);
    }

    public PlanReconciliationService(double slThresholdAtr,
                                     double rrThreshold,
                                     double entryThresholdAtr) {
        if (slThresholdAtr <= 0 || rrThreshold <= 0 || entryThresholdAtr <= 0) {
            throw new IllegalArgumentException("all thresholds must be > 0");
        }
        this.slThresholdAtr = BigDecimal.valueOf(slThresholdAtr);
        this.rrThreshold = BigDecimal.valueOf(rrThreshold);
        this.entryThresholdAtr = BigDecimal.valueOf(entryThresholdAtr);
    }

    /**
     * Compares two plans and returns a structured divergence report.
     * Returns {@link PlanReconciliationResult#aligned()} when any plan is null/incomplete
     * (no comparison possible — caller decides whether to veto or proceed).
     */
    public PlanReconciliationResult reconcile(BigDecimal mechEntry, BigDecimal mechSl, double mechRr,
                                              BigDecimal aiEntry,   BigDecimal aiSl,   double aiRr,
                                              BigDecimal atr) {
        if (atr == null || atr.signum() <= 0) {
            return PlanReconciliationResult.aligned();
        }
        if (mechEntry == null || mechSl == null || aiEntry == null || aiSl == null) {
            return PlanReconciliationResult.aligned();
        }

        BigDecimal slDiv = mechSl.subtract(aiSl).abs()
            .divide(atr, 4, RoundingMode.HALF_UP);
        BigDecimal entryDiv = mechEntry.subtract(aiEntry).abs()
            .divide(atr, 4, RoundingMode.HALF_UP);
        BigDecimal rrDiv = BigDecimal.valueOf(Math.abs(mechRr - aiRr))
            .setScale(4, RoundingMode.HALF_UP);

        List<String> reasons = new ArrayList<>(3);
        boolean mismatch = false;
        if (slDiv.compareTo(slThresholdAtr) > 0) {
            mismatch = true;
            reasons.add(String.format("SL divergence %.2f×ATR exceeds %.2f×ATR threshold",
                slDiv, slThresholdAtr));
        }
        if (rrDiv.compareTo(rrThreshold) > 0) {
            mismatch = true;
            reasons.add(String.format("R:R divergence %.2f exceeds %.2f threshold",
                rrDiv, rrThreshold));
        }
        if (entryDiv.compareTo(entryThresholdAtr) > 0) {
            mismatch = true;
            reasons.add(String.format("Entry divergence %.2f×ATR exceeds %.2f×ATR threshold",
                entryDiv, entryThresholdAtr));
        }

        if (!mismatch) {
            return PlanReconciliationResult.aligned();
        }
        return new PlanReconciliationResult(true, slDiv, rrDiv, entryDiv, List.copyOf(reasons));
    }

    public BigDecimal slThresholdAtr()    { return slThresholdAtr; }
    public BigDecimal rrThreshold()       { return rrThreshold; }
    public BigDecimal entryThresholdAtr() { return entryThresholdAtr; }
}
