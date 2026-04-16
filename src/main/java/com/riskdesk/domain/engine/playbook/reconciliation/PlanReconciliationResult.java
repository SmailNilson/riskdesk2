package com.riskdesk.domain.engine.playbook.reconciliation;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of comparing the mechanical plan (deterministic) with the Gemini-proposed
 * plan (AI). Used for observability and as a veto signal when the two plans disagree
 * meaningfully.
 *
 * @param mismatch        true when any individual divergence exceeds its threshold
 * @param slDivergenceAtr absolute SL gap expressed in multiples of ATR
 *                        ({@code |sl_mech - sl_gemini| / atr})
 * @param rrDivergence    absolute difference {@code |rr_mech - rr_gemini|}
 * @param entryDivergenceAtr absolute entry gap in multiples of ATR
 * @param reasons         human-readable explanation lines (empty when {@code !mismatch})
 */
public record PlanReconciliationResult(
    boolean mismatch,
    BigDecimal slDivergenceAtr,
    BigDecimal rrDivergence,
    BigDecimal entryDivergenceAtr,
    List<String> reasons
) {
    public static PlanReconciliationResult aligned() {
        return new PlanReconciliationResult(false,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }
}
