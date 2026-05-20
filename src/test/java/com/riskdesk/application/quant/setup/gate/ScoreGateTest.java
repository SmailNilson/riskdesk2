package com.riskdesk.application.quant.setup.gate;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupEvaluationContext;
import com.riskdesk.domain.quant.structure.StructuralFilterResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreGateTest {

    private static final ZonedDateTime SCAN_TIME =
        ZonedDateTime.of(2026, 4, 29, 10, 0, 0, 0, ZoneId.of("America/New_York"));

    private final ScoreGate gate = new ScoreGate();

    private static QuantSnapshot snapshot(int shortRaw, int longRaw,
                                           int shortStructuralModifier,
                                           int longStructuralModifier) {
        // Build with the post-LONG-symmetry constructor (no structural data),
        // then attach structural modifiers via withStructuralResult.
        QuantSnapshot raw = new QuantSnapshot(
            Instrument.MCL, Map.<Gate, GateResult>of(),
            shortRaw, longRaw, 20_000.0, "TEST", 0.0, SCAN_TIME
        );
        StructuralFilterResult shortSr = new StructuralFilterResult(
            List.of(), List.of(), shortStructuralModifier, false);
        StructuralFilterResult longSr = new StructuralFilterResult(
            List.of(), List.of(), longStructuralModifier, false);
        return raw.withStructuralResult(shortSr).withLongStructuralResult(longSr);
    }

    private static SetupEvaluationContext ctxFor(QuantSnapshot snap) {
        return new SetupEvaluationContext(
            Instrument.MCL, snap, null, null, Instant.parse("2026-04-29T14:00:00Z")
        );
    }

    @Test
    @DisplayName("raw 6/0 short, no structural penalty → passes")
    void raw_passes_when_no_penalty() {
        GateCheckResult r = gate.check(ctxFor(snapshot(6, 0, 0, 0)));
        assertThat(r.passed()).isTrue();
    }

    @Test
    @DisplayName("raw 5 short reduced to 3 by structural -2 → fails (regression for P1)")
    void raw_5_with_minus_2_penalty_fails() {
        GateCheckResult r = gate.check(ctxFor(snapshot(5, 0, -2, 0)));
        assertThat(r.passed())
            .as("setup must NOT qualify when structural warnings push adjusted score below threshold")
            .isFalse();
        assertThat(r.reason()).contains("short=3").contains("min=5");
    }

    @Test
    @DisplayName("raw 7 short reduced to 5 by structural -2 → passes (still meets threshold)")
    void raw_7_with_minus_2_penalty_passes() {
        GateCheckResult r = gate.check(ctxFor(snapshot(7, 0, -2, 0)));
        assertThat(r.passed()).isTrue();
    }

    @Test
    @DisplayName("raw 4/4 → fails (neither direction meets threshold)")
    void low_raw_both_fail() {
        GateCheckResult r = gate.check(ctxFor(snapshot(4, 4, 0, 0)));
        assertThat(r.passed()).isFalse();
    }

    @Test
    @DisplayName("LONG side 6 with -3 penalty (becomes 3) but SHORT 5 unmodified → SHORT carries the gate")
    void short_unmodified_carries_gate() {
        GateCheckResult r = gate.check(ctxFor(snapshot(5, 6, 0, -3)));
        assertThat(r.passed()).isTrue();
        assertThat(r.reason()).contains("short=5").contains("long=3");
    }
}
