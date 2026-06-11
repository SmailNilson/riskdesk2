package com.riskdesk.domain.quant.scanlog;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.DeltaSnapshot;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.MarketSnapshot;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Mapping tests for the per-scan flow-log factory. */
class QuantScanSnapshotTest {

    private static final Instant SCAN_AT = Instant.parse("2026-06-11T14:30:00Z");

    @Test
    void capturesInputsOutputsAndGateVerdicts() {
        MarketSnapshot inputs = new MarketSnapshot.Builder()
            .now(SCAN_AT)
            .price(28518.25)
            .priceSource("LIVE_PUSH")
            .delta(-260.0)
            .buyPct(46.5)
            .absFresh(9)
            .absBull8(1)
            .absBear8(5)
            .absMaxScore(8.7)
            .dist("DISTRIBUTION", 72)
            .cycle("MARKDOWN", "LATE")
            .build();

        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        gates.put(Gate.G0_REGIME, GateResult.pass("dayMove=12"));
        gates.put(Gate.G3_DELTA_NEG, GateResult.fail("Δ=-260 ≥ -100? no"));
        gates.put(Gate.L3_DELTA_POS, GateResult.abstain("Δ=ABSTAIN (feed down)"));
        QuantSnapshot evaluated = new QuantSnapshot(
            Instrument.MNQ, gates, 5, 2, 28518.25, "LIVE_PUSH", 0.0,
            ZonedDateTime.ofInstant(SCAN_AT, ZoneId.of("America/New_York")));

        DeltaSnapshot raw = new DeltaSnapshot(-260.0, 46.5, SCAN_AT, "REAL_TICKS");
        PatternAnalysis pattern = new PatternAnalysis(
            OrderFlowPattern.DISTRIBUTION_SILENCIEUSE, "Distribution silencieuse",
            "Δ=-260 | [Δ CONFIRMED][ABS BEAR ACTIVE]",
            PatternAnalysis.Confidence.HIGH, PatternAnalysis.Action.TRADE);

        QuantScanSnapshot row = QuantScanSnapshot.from(inputs, raw, evaluated, pattern);

        assertThat(row.instrument()).isEqualTo(Instrument.MNQ);
        assertThat(row.scannedAt()).isEqualTo(SCAN_AT);
        assertThat(row.price()).isEqualTo(28518.25);
        assertThat(row.delta()).isEqualTo(-260.0);
        assertThat(row.buyRatioPct()).isEqualTo(46.5);
        assertThat(row.deltaSource()).isEqualTo("REAL_TICKS");
        assertThat(row.absBear8Count()).isEqualTo(5);
        assertThat(row.dominantSide()).isEqualTo("BEAR");
        assertThat(row.distType()).isEqualTo("DISTRIBUTION");
        assertThat(row.distConf()).isEqualTo(72);
        assertThat(row.score()).isEqualTo(5);
        assertThat(row.longScore()).isEqualTo(2);
        assertThat(row.patternType()).isEqualTo("DISTRIBUTION_SILENCIEUSE");
        assertThat(row.patternConfidence()).isEqualTo("HIGH");
        assertThat(row.patternActionShort()).isEqualTo("TRADE");
        assertThat(row.gateResults())
            .containsEntry("G0_REGIME", "PASS — dayMove=12")
            .containsEntry("G3_DELTA_NEG", "FAIL — Δ=-260 ≥ -100? no")
            .containsEntry("L3_DELTA_POS", "ABSTAIN — Δ=ABSTAIN (feed down)");
    }

    @Test
    void staleDropKeepsRawProvenanceWhileGateDeltaIsNull() {
        // buildSnapshot nulls a stale delta before the gates see it, but the
        // raw window still carries its provenance — the log must record BOTH
        // so the stale-drop case is distinguishable from "no window at all".
        MarketSnapshot inputs = new MarketSnapshot.Builder()
            .now(SCAN_AT).price(28518.25).priceSource("LIVE_PUSH")
            .delta(null).buyPct(null)
            .build();
        QuantSnapshot evaluated = new QuantSnapshot(
            Instrument.MNQ, new EnumMap<>(Gate.class), 0, 0, 28518.25, "LIVE_PUSH", 0.0,
            ZonedDateTime.ofInstant(SCAN_AT, ZoneId.of("America/New_York")));
        DeltaSnapshot raw = new DeltaSnapshot(-512.0, 44.0, SCAN_AT.minusSeconds(120), "REAL_TICKS_TICKRULE");

        QuantScanSnapshot row = QuantScanSnapshot.from(inputs, raw, evaluated, null);

        assertThat(row.delta()).isNull();
        assertThat(row.buyRatioPct()).isNull();
        assertThat(row.deltaSource()).isEqualTo("REAL_TICKS_TICKRULE");
        assertThat(row.patternType()).isNull();
        assertThat(row.gateResults()).isEmpty();
    }

    @Test
    void noWindowAtAllLeavesProvenanceNull() {
        MarketSnapshot inputs = new MarketSnapshot.Builder()
            .now(SCAN_AT).price(null).priceSource("")
            .build();
        QuantSnapshot evaluated = new QuantSnapshot(
            Instrument.MCL, new EnumMap<>(Gate.class), 0, 0, null, "", 0.0,
            ZonedDateTime.ofInstant(SCAN_AT, ZoneId.of("America/New_York")));

        QuantScanSnapshot row = QuantScanSnapshot.from(inputs, null, evaluated, null);

        assertThat(row.delta()).isNull();
        assertThat(row.deltaSource()).isNull();
        assertThat(row.price()).isNull();
    }
}
