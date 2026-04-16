package com.riskdesk.domain.engine.playbook.reconciliation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanReconciliationServiceTest {

    private final PlanReconciliationService svc = new PlanReconciliationService();

    // ── Aligned plans ────────────────────────────────────────────────────────

    @Test
    void plans_with_same_values_are_aligned() {
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("10"));
        assertThat(r.mismatch()).isFalse();
        assertThat(r.reasons()).isEmpty();
    }

    @Test
    void plans_with_small_divergence_under_thresholds_are_aligned() {
        // ATR=10. SL gap=5 → 0.5×ATR < 1.5. RR gap=0.2 < 0.5. Entry gap=3 → 0.3×ATR < 0.75.
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("18003"), new BigDecimal("17985"), 2.2,
            new BigDecimal("10"));
        assertThat(r.mismatch()).isFalse();
    }

    // ── SL divergence ────────────────────────────────────────────────────────

    @Test
    void flags_mismatch_when_sl_gap_exceeds_15_atr() {
        // ATR=10, mechSl=17980, aiSl=17960 → gap=20 = 2.0×ATR > 1.5
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("18000"), new BigDecimal("17960"), 2.0,
            new BigDecimal("10"));
        assertThat(r.mismatch()).isTrue();
        assertThat(r.slDivergenceAtr()).isEqualByComparingTo("2.0000");
        assertThat(r.reasons()).anyMatch(s -> s.contains("SL divergence"));
    }

    // ── R:R divergence ───────────────────────────────────────────────────────

    @Test
    void flags_mismatch_when_rr_gap_exceeds_05() {
        // mechRr=2.0, aiRr=1.3 → gap=0.7 > 0.5
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("18000"), new BigDecimal("17980"), 1.3,
            new BigDecimal("10"));
        assertThat(r.mismatch()).isTrue();
        assertThat(r.rrDivergence()).isEqualByComparingTo("0.7000");
        assertThat(r.reasons()).anyMatch(s -> s.contains("R:R divergence"));
    }

    // ── Entry divergence ─────────────────────────────────────────────────────

    @Test
    void flags_mismatch_when_entry_gap_exceeds_075_atr() {
        // ATR=10, mechEntry=18000, aiEntry=18010 → gap=10 = 1.0×ATR > 0.75
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("18010"), new BigDecimal("17990"), 2.0,
            new BigDecimal("10"));
        assertThat(r.mismatch()).isTrue();
        assertThat(r.entryDivergenceAtr()).isEqualByComparingTo("1.0000");
    }

    // ── Multiple divergences accumulate reasons ──────────────────────────────

    @Test
    void accumulates_all_reasons_when_multiple_axes_diverge() {
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("18010"), new BigDecimal("17950"), 1.2,
            new BigDecimal("10"));
        assertThat(r.mismatch()).isTrue();
        assertThat(r.reasons()).hasSize(3);
    }

    // ── Null / incomplete plans gracefully return aligned ───────────────────

    @Test
    void null_atr_returns_aligned() {
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("18000"), new BigDecimal("17960"), 2.0,
            null);
        assertThat(r.mismatch()).isFalse();
    }

    @Test
    void zero_atr_returns_aligned() {
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("18000"), new BigDecimal("17960"), 2.0,
            BigDecimal.ZERO);
        assertThat(r.mismatch()).isFalse();
    }

    @Test
    void null_mechanical_plan_returns_aligned() {
        PlanReconciliationResult r = svc.reconcile(
            null, null, 0.0,
            new BigDecimal("18000"), new BigDecimal("17960"), 2.0,
            new BigDecimal("10"));
        assertThat(r.mismatch()).isFalse();
    }

    @Test
    void null_ai_plan_returns_aligned() {
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            null, null, 0.0,
            new BigDecimal("10"));
        assertThat(r.mismatch()).isFalse();
    }

    // ── Custom thresholds ────────────────────────────────────────────────────

    @Test
    void custom_thresholds_are_honored() {
        // Very strict: flag any SL gap > 0.2×ATR
        PlanReconciliationService strict = new PlanReconciliationService(0.2, 0.1, 0.1);
        PlanReconciliationResult r = strict.reconcile(
            new BigDecimal("18000"), new BigDecimal("17980"), 2.0,
            new BigDecimal("18000"), new BigDecimal("17975"), 2.0,  // 0.5×ATR gap
            new BigDecimal("10"));
        assertThat(r.mismatch()).isTrue();
    }

    @Test
    void rejects_non_positive_thresholds_in_constructor() {
        assertThatThrownBy(() -> new PlanReconciliationService(0.0, 0.5, 0.75))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlanReconciliationService(1.5, -0.1, 0.75))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Boundary: exactly at threshold ───────────────────────────────────────

    @Test
    void exactly_at_sl_threshold_is_not_a_mismatch() {
        // slDiv == 1.5 is NOT >, so aligned
        PlanReconciliationResult r = svc.reconcile(
            new BigDecimal("18000"), new BigDecimal("17985"), 2.0,
            new BigDecimal("18000"), new BigDecimal("17970"), 2.0,  // gap=15 = 1.5×ATR
            new BigDecimal("10"));
        assertThat(r.mismatch()).isFalse();
    }
}
