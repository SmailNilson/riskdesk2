package com.riskdesk.domain.quant.setup;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SetupGateChainTest {

    private static final ZonedDateTime SCAN_TIME =
        ZonedDateTime.of(2026, 4, 29, 10, 0, 0, 0, ZoneId.of("America/New_York"));

    // A minimal valid snapshot: score 6, price available, not blocked
    private static QuantSnapshot goodSnapshot() {
        return new QuantSnapshot(
            Instrument.MCL, Map.of(), 6, 6,
            20_000.0, "TEST", 15.0, SCAN_TIME
        );
    }

    // A snapshot with null price
    private static QuantSnapshot noPriceSnapshot() {
        return new QuantSnapshot(
            Instrument.MCL, Map.of(), 6, 6,
            null, "TEST", 0.0, SCAN_TIME
        );
    }

    // A snapshot below score threshold
    private static QuantSnapshot lowScoreSnapshot() {
        return new QuantSnapshot(
            Instrument.MCL, Map.of(), 2, 2,
            20_000.0, "TEST", 0.0, SCAN_TIME
        );
    }

    @Test
    @DisplayName("all gates pass — allPassed returns true")
    void allGates_pass() {
        SetupEvaluationContext ctx = new SetupEvaluationContext(
            Instrument.MCL, goodSnapshot(), null, null,
            Instant.parse("2026-04-29T14:00:00Z")   // 10:00 ET — inside window
        );

        SetupGateChain chain = new SetupGateChain(List.of(
            c -> GateCheckResult.pass("G1", "ok"),
            c -> GateCheckResult.pass("G2", "ok")
        ));

        List<GateCheckResult> results = chain.evaluateAll(ctx);
        assertThat(SetupGateChain.allPassed(results)).isTrue();
    }

    @Test
    @DisplayName("one gate fails — allPassed returns false")
    void oneGate_fails() {
        SetupEvaluationContext ctx = new SetupEvaluationContext(
            Instrument.MCL, goodSnapshot(), null, null,
            Instant.parse("2026-04-29T14:00:00Z")
        );

        SetupGateChain chain = new SetupGateChain(List.of(
            c -> GateCheckResult.pass("G1", "ok"),
            c -> GateCheckResult.fail("G2", "blocked")
        ));

        List<GateCheckResult> results = chain.evaluateAll(ctx);
        assertThat(SetupGateChain.allPassed(results)).isFalse();
        assertThat(results).hasSize(2);
        assertThat(results.get(1).passed()).isFalse();
    }

    @Test
    @DisplayName("empty chain — allPassed is trivially true")
    void emptyChain_triviallyPasses() {
        SetupEvaluationContext ctx = new SetupEvaluationContext(
            Instrument.MCL, goodSnapshot(), null, null, Instant.now()
        );
        SetupGateChain chain = new SetupGateChain(List.of());
        assertThat(SetupGateChain.allPassed(chain.evaluateAll(ctx))).isTrue();
    }
}
