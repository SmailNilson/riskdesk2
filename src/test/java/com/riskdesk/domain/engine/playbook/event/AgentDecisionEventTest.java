package com.riskdesk.domain.engine.playbook.event;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests on {@link AgentDecisionEvent}: verifies the record-canonical shape,
 * defensive copying of the verdicts list, and null-safe handling of a missing list.
 */
class AgentDecisionEventTest {

    @Test
    void record_carriesAllFields() {
        var verdicts = List.of(
            new AgentDecisionEvent.AgentSummary(
                "MTF-Confluence-AI", Confidence.HIGH, "Triple HTF bullish", false),
            new AgentDecisionEvent.AgentSummary(
                "Session-Timing", Confidence.LOW, "ASIAN — LOW LIQUIDITY", false));

        Instant t = Instant.parse("2026-04-15T14:00:00Z");
        AgentDecisionEvent event = new AgentDecisionEvent(
            Instrument.MCL, "10m", "ZONE_RETEST", "ELIGIBLE",
            0.0085, verdicts, 1, "LONG — ZONE RETEST — 6/7 — 1 warning(s)", t);

        assertEquals(Instrument.MCL, event.instrument());
        assertEquals("10m", event.timeframe());
        assertEquals("ZONE_RETEST", event.setupType());
        assertEquals("ELIGIBLE", event.eligibility());
        assertEquals(0.0085, event.sizePercent(), 1e-9);
        assertEquals(2, event.verdicts().size());
        assertEquals(1, event.warningsCount());
        assertEquals(t, event.timestamp());
    }

    @Test
    void verdictsList_isDefensivelyCopied() {
        List<AgentDecisionEvent.AgentSummary> mutable = new ArrayList<>();
        mutable.add(new AgentDecisionEvent.AgentSummary(
            "Session-Timing", Confidence.HIGH, "KILL ZONE", false));

        AgentDecisionEvent event = new AgentDecisionEvent(
            Instrument.MCL, "10m", "ZONE_RETEST", "ELIGIBLE",
            0.01, mutable, 0, "ok", Instant.now());

        // Mutating the caller's list must NOT affect the event's view — event sourcing
        // breaks badly if a downstream listener sees a snapshot different from the emitter.
        mutable.add(new AgentDecisionEvent.AgentSummary(
            "MTF-Confluence-AI", Confidence.LOW, "late", false));

        assertEquals(1, event.verdicts().size());
    }

    @Test
    void nullVerdicts_normalizeToEmpty() {
        AgentDecisionEvent event = new AgentDecisionEvent(
            Instrument.MCL, "10m", "ZONE_RETEST", "INELIGIBLE",
            0.0, null, 0, "no setup", Instant.now());

        assertNotNull(event.verdicts());
        assertTrue(event.verdicts().isEmpty());
    }

    @Test
    void implementsDomainEvent_contract() {
        AgentDecisionEvent event = new AgentDecisionEvent(
            Instrument.MCL, "10m", "ZONE_RETEST", "ELIGIBLE",
            0.01, List.of(), 0, "ok", Instant.parse("2026-04-15T14:00:00Z"));

        // timestamp() is from DomainEvent — must be reachable through that contract
        com.riskdesk.domain.shared.event.DomainEvent de = event;
        assertEquals(Instant.parse("2026-04-15T14:00:00Z"), de.timestamp());
    }
}
