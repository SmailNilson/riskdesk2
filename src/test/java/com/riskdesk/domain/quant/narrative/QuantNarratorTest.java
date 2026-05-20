package com.riskdesk.domain.quant.narrative;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuantNarratorTest {

    private final QuantNarrator narrator = new QuantNarrator();

    @Test
    void rendersAllFourteenGatesWithIcons() {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        for (Gate g : Gate.values()) gates.put(g, GateResult.pass("ok " + g.name()));
        QuantSnapshot snap = new QuantSnapshot(Instrument.MNQ, gates, 7, 7, 20_000.0, "LIVE_PUSH",
            12.5, ZonedDateTime.of(2026, 4, 29, 14, 0, 0, 0, ZoneId.of("America/New_York")));
        PatternAnalysis pattern = new PatternAnalysis(OrderFlowPattern.DISTRIBUTION_SILENCIEUSE,
            "Distribution silencieuse", "Δ négatif + prix stable", PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.TRADE);

        String md = narrator.narrate(Instrument.MNQ, snap, pattern);

        assertThat(md).contains("MNQ — Quant SHORT 7/7");
        assertThat(md).contains("LONG 7/7");
        assertThat(md).contains("✅ **G0 Régime**");
        assertThat(md).contains("✅ **G6 LIVE_PUSH**");
        assertThat(md).contains("✅ **L0 Régime**");
        assertThat(md).contains("✅ **L6 LIVE_PUSH**");
        assertThat(md).contains("Distribution silencieuse");
        assertThat(md).contains("🔔 SHORT 7/7");
        assertThat(md).contains("🔔 LONG 7/7");
        // SHORT plan
        assertThat(md).contains("SL    20025.00");
        assertThat(md).contains("TP1   19960.00");
        // LONG plan
        assertThat(md).contains("SL    19975.00");
        assertThat(md).contains("TP1   20040.00");
    }

    @Test
    void rendersFailedGatesWithCross() {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        for (Gate g : Gate.values()) gates.put(g, GateResult.pass("ok"));
        gates.put(Gate.G6_LIVE_PUSH, GateResult.fail("source=DB_FALLBACK"));
        QuantSnapshot snap = new QuantSnapshot(Instrument.MNQ, gates, 6, 20_000.0, "DB_FALLBACK",
            0.0, ZonedDateTime.now(ZoneId.of("America/New_York")));

        String md = narrator.narrate(Instrument.MNQ, snap, null);

        assertThat(md).contains("❌ **G6 LIVE_PUSH** — source=DB_FALLBACK");
        assertThat(md).contains("⚠️ SHORT setup 6/7");
    }
}
