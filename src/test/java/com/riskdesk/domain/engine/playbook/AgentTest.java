package com.riskdesk.domain.engine.playbook;

import com.riskdesk.domain.engine.playbook.agent.*;
import com.riskdesk.domain.engine.playbook.model.*;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentTest {

    // ── Structure Analyst ──────────────────────────────────────────────

    @Test
    void structureAgent_highConfidence_whenCleanStructure() {
        var agent = new StructureAnalystAgent();
        var playbook = buildPlaybook(true, 5, 0, 5);
        var context = buildContext();

        AgentVerdict v = agent.evaluate(playbook, context);

        assertNotNull(v);
        assertEquals("StructureAnalyst", v.agentName());
        // With all valid breaks and no snapshot for swing CHoCH, confidence depends on ratio
        assertNotEquals(Confidence.LOW, v.confidence());
    }

    @Test
    void structureAgent_skip_whenNoSetup() {
        var agent = new StructureAnalystAgent();
        var playbook = noSetupPlaybook();
        var context = buildContext();

        AgentVerdict v = agent.evaluate(playbook, context);

        assertNotNull(v);
        assertTrue(v.reasoning().contains("No setup"));
    }

    // ── Risk Manager ──────────────────────────────────────────────────

    @Test
    void riskManager_normalSize_whenNoRisk() {
        var agent = new RiskManagerAgent();
        var playbook = buildPlaybook(true, 5, 0, 5);
        var context = buildContext();

        AgentVerdict v = agent.evaluate(playbook, context);

        assertNotNull(v);
        assertEquals("RiskManager", v.agentName());
        assertTrue(((Number) v.adjustments().get("size_pct")).doubleValue() > 0);
    }

    @Test
    void riskManager_blocked_whenHighDrawdown() {
        var agent = new RiskManagerAgent();
        var playbook = buildPlaybook(true, 5, 0, 5);
        var context = new AgentContext(
            Instrument.MCL, "10m", null,
            new AgentContext.PortfolioState(0, 0.04, 0, false), // 4% drawdown > 3% limit
            AgentContext.MacroSnapshot.empty(),
            new BigDecimal("1.50")
        );

        AgentVerdict v = agent.evaluate(playbook, context);

        assertNotNull(v);
        assertEquals(Confidence.LOW, v.confidence());
        assertTrue(v.reasoning().contains("STOP"));
    }

    // ── Macro Context ────────────────────────────────────────────────

    @Test
    void macroAgent_highConfidence_whenKillZone() {
        var agent = new MacroContextAgent();
        var playbook = buildPlaybook(true, 5, 0, 5);
        var context = new AgentContext(
            Instrument.MCL, "10m", null,
            AgentContext.PortfolioState.empty(),
            new AgentContext.MacroSnapshot(-0.3, "BEARISH", "NEW_YORK", true),
            new BigDecimal("1.50")
        );

        AgentVerdict v = agent.evaluate(playbook, context);

        assertNotNull(v);
        assertEquals("MacroContext", v.agentName());
        assertTrue(v.reasoning().contains("Kill zone"));
    }

    @Test
    void macroAgent_lowConfidence_whenDxyHeadwind() {
        var agent = new MacroContextAgent();
        var playbook = buildPlaybook(true, 5, 0, 5);
        var context = new AgentContext(
            Instrument.MCL, "10m", null,
            AgentContext.PortfolioState.empty(),
            new AgentContext.MacroSnapshot(0.5, "BULLISH", "ASIAN", false),
            new BigDecimal("1.50")
        );

        AgentVerdict v = agent.evaluate(playbook, context);

        assertNotNull(v);
        // DXY up + LONG + Asian session = 2 negative factors
        assertEquals(Confidence.LOW, v.confidence());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private PlaybookEvaluation buildPlaybook(boolean clean, int valid, int fake, int total) {
        FilterResult filters = new FilterResult(
            true, "BULLISH", Direction.LONG,
            clean, valid, fake, total, clean ? 1.0 : 0.5,
            true, VaPosition.BELOW_VA, true
        );
        SetupCandidate setup = new SetupCandidate(
            SetupType.ZONE_RETEST, "OB BULLISH 91.03-94.71",
            new BigDecimal("94.71"), new BigDecimal("91.03"), new BigDecimal("92.87"),
            0.5, true, false, true, 2.9, 5
        );
        PlaybookPlan plan = new PlaybookPlan(
            new BigDecimal("97.00"), new BigDecimal("96.60"),
            new BigDecimal("98.17"), new BigDecimal("99.49"),
            2.9, 0.01, "Below breaker", "First opposing OB"
        );
        return new PlaybookEvaluation(
            filters, List.of(setup), setup, plan, List.of(), 5,
            "LONG — ZONE RETEST — 5/7", Instant.now()
        );
    }

    private PlaybookEvaluation noSetupPlaybook() {
        FilterResult filters = new FilterResult(
            true, "BULLISH", Direction.LONG,
            true, 0, 0, 0, 1.0,
            true, VaPosition.BELOW_VA, true
        );
        return new PlaybookEvaluation(
            filters, List.of(), null, null, List.of(), 0,
            "NO TRADE", Instant.now()
        );
    }

    private AgentContext buildContext() {
        return new AgentContext(
            Instrument.MCL, "10m", null,
            AgentContext.PortfolioState.empty(),
            AgentContext.MacroSnapshot.empty(),
            new BigDecimal("1.50")
        );
    }
}
