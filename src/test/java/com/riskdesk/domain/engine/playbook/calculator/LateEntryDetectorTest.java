package com.riskdesk.domain.engine.playbook.calculator;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.playbook.model.PlaybookPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-8 · Detects when price has advanced past the planned entry in the
 * trade direction by more than the ATR tolerance. Chasing such a setup
 * would mean taking a late entry with degraded R:R.
 */
class LateEntryDetectorTest {

    private static final BigDecimal ATR_1_0 = new BigDecimal("1.00");
    private static final BigDecimal ENTRY_100 = new BigDecimal("100.00");

    private static PlaybookPlan planAt(BigDecimal entry) {
        return new PlaybookPlan(entry, entry.subtract(BigDecimal.ONE),
                entry.add(BigDecimal.TWO), entry.add(new BigDecimal("3")),
                2.0, 0.01, "sl", "tp");
    }

    // ── LONG semantics: late when lastPrice is ABOVE entry + tolerance ─────

    @Test
    void longIsLate_whenLastPriceIsAboveEntryPlusTolerance() {
        LateEntryDetector d = new LateEntryDetector(0.5); // tolerance = 0.5 × 1.0 = 0.5
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("100.60"), ATR_1_0, Direction.LONG))
                .as("price drifted 0.60 past entry, tolerance 0.50 → late")
                .isTrue();
    }

    @Test
    void longIsNotLate_whenLastPriceWithinTolerance() {
        LateEntryDetector d = new LateEntryDetector(0.5);
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("100.40"), ATR_1_0, Direction.LONG))
                .as("0.40 advance is inside 0.50 tolerance — still OK to enter")
                .isFalse();
    }

    @Test
    void longIsNotLate_whenLastPriceBelowEntry() {
        LateEntryDetector d = new LateEntryDetector(0.5);
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("99.00"), ATR_1_0, Direction.LONG))
                .as("price still below entry — we are waiting for a pullback, not late")
                .isFalse();
    }

    @Test
    void longIsNotLate_atExactTolerance() {
        LateEntryDetector d = new LateEntryDetector(0.5);
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("100.50"), ATR_1_0, Direction.LONG))
                .as("strict '>' semantics — equal to tolerance is not late")
                .isFalse();
    }

    // ── SHORT semantics: late when lastPrice is BELOW entry - tolerance ────

    @Test
    void shortIsLate_whenLastPriceBelowEntryMinusTolerance() {
        LateEntryDetector d = new LateEntryDetector(0.5);
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("99.40"), ATR_1_0, Direction.SHORT))
                .as("price dropped 0.60 past entry, tolerance 0.50 → late")
                .isTrue();
    }

    @Test
    void shortIsNotLate_whenLastPriceAboveEntry() {
        LateEntryDetector d = new LateEntryDetector(0.5);
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("101.00"), ATR_1_0, Direction.SHORT))
                .as("price still above entry — waiting for rejection, not late")
                .isFalse();
    }

    // ── Configurable multiplier ────────────────────────────────────────────

    @Test
    void customMultiplier_tightenLateEntryThreshold() {
        LateEntryDetector d = new LateEntryDetector(0.2); // tolerance = 0.20
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("100.30"), ATR_1_0, Direction.LONG))
                .as("custom 0.2 × ATR multiplier catches what the default 0.5 would miss")
                .isTrue();
    }

    @Test
    void defaultMultiplier_isHalfAtr() {
        assertThat(new LateEntryDetector().atrMultiplier())
                .isEqualTo(LateEntryDetector.DEFAULT_ATR_MULTIPLIER)
                .isEqualTo(0.5);
    }

    @Test
    void constructor_rejectsNonPositiveMultiplier() {
        assertThatThrownBy(() -> new LateEntryDetector(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LateEntryDetector(-0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Fail-open defaults ─────────────────────────────────────────────────

    @Test
    void returnsFalse_whenPlanIsNull() {
        LateEntryDetector d = new LateEntryDetector();
        assertThat(d.isLate(null, new BigDecimal("150"), ATR_1_0, Direction.LONG)).isFalse();
    }

    @Test
    void returnsFalse_whenEntryPriceIsNull() {
        LateEntryDetector d = new LateEntryDetector();
        PlaybookPlan nullEntry = new PlaybookPlan(null, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.TEN, 2.0, 0.01, "sl", "tp");
        assertThat(d.isLate(nullEntry, new BigDecimal("150"), ATR_1_0, Direction.LONG)).isFalse();
    }

    @Test
    void returnsFalse_whenLastPriceIsNull() {
        LateEntryDetector d = new LateEntryDetector();
        assertThat(d.isLate(planAt(ENTRY_100), null, ATR_1_0, Direction.LONG)).isFalse();
    }

    @Test
    void returnsFalse_whenAtrIsNullOrZero() {
        LateEntryDetector d = new LateEntryDetector();
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("150"), null, Direction.LONG)).isFalse();
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("150"), BigDecimal.ZERO, Direction.LONG)).isFalse();
    }

    @Test
    void returnsFalse_whenDirectionIsNull() {
        LateEntryDetector d = new LateEntryDetector();
        assertThat(d.isLate(planAt(ENTRY_100), new BigDecimal("150"), ATR_1_0, null)).isFalse();
    }
}
