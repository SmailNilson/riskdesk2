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

    // ── MTF Confluence Agent ────────────────────────────────────────────

    @Test
    void mtfAgent_tripleConfluence_highConfidence() {
        var agent = new MtfConfluenceAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var mtf = new AgentContext.MtfSnapshot("BULLISH", "BULLISH", "BULLISH", "BULLISH", "BOS", "BOS");
        var context = contextWith(mtf, null, null);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.HIGH, v.confidence());
        assertTrue(v.reasoning().contains("Triple confluence"));
    }

    @Test
    void mtfAgent_h4Conflicts_lowConfidence() {
        var agent = new MtfConfluenceAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var mtf = new AgentContext.MtfSnapshot("BULLISH", "BULLISH", "BEARISH", null, "BOS", "CHOCH");
        var context = contextWith(mtf, null, null);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.LOW, v.confidence());
        assertTrue(v.reasoning().contains("H4"));
    }

    @Test
    void mtfAgent_skip_whenNoSetup() {
        var agent = new MtfConfluenceAgent();
        var playbook = noSetupPlaybook();

        AgentVerdict v = agent.evaluate(playbook, contextWith(null, null, null));

        assertTrue(v.reasoning().contains("No setup"));
    }

    // ── Divergence Hunter Agent ─────────────────────────────────────────

    @Test
    void divergenceAgent_momentumAligned_highConfidence() {
        var agent = new DivergenceHunterAgent();
        var playbook = mockPlaybook(Direction.LONG, 5);
        var momentum = new AgentContext.MomentumSnapshot(
            bd("35"), "NEUTRAL", bd("0.15"), "BULLISH",
            bd("-20"), bd("-25"), "NEUTRAL",
            bd("0.40"), false, null, true, "NEUTRAL", null
        );
        var context = contextWith(null, momentum, null);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.HIGH, v.confidence());
    }

    @Test
    void divergenceAgent_rsiOverbought_forLong_warning() {
        var agent = new DivergenceHunterAgent();
        var playbook = mockPlaybook(Direction.LONG, 5);
        var momentum = new AgentContext.MomentumSnapshot(
            bd("75"), "OVERBOUGHT", bd("-0.10"), "BEARISH",
            bd("65"), bd("60"), "OVERBOUGHT",
            bd("0.90"), true, null, true, "OVERBOUGHT", null
        );
        var context = contextWith(null, momentum, null);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.LOW, v.confidence());
    }

    // ── Correlation Guard Agent ─────────────────────────────────────────

    @Test
    void correlationAgent_noRisk_highConfidence() {
        var agent = new CorrelationGuardAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var context = contextWithPortfolio(0, 0, 0, false, 20.0);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.HIGH, v.confidence());
        assertTrue(v.reasoning().contains("No risk concerns"));
    }

    @Test
    void correlationAgent_highDrawdown_blocked() {
        var agent = new CorrelationGuardAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var context = contextWithPortfolio(-5000, 4.5, 2, false, 60.0);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.LOW, v.confidence());
        assertTrue((boolean) v.adjustments().get("blocked"));
        assertTrue(v.reasoning().contains("DAILY DRAWDOWN"));
    }

    @Test
    void correlationAgent_dxyHeadwind_warning() {
        var agent = new CorrelationGuardAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var macro = new AgentContext.MacroSnapshot(0.35, "BULLISH", "DIVERGENT", "DXY_ONLY", "NY_AM", true);
        var portfolio = new AgentContext.PortfolioState(0, 0, 0, false, 20);
        var context = new AgentContext(Instrument.MCL, "10m", minimalInput(), portfolio, macro,
            AgentContext.MtfSnapshot.empty(), AgentContext.MomentumSnapshot.empty(),
            AgentContext.SessionInfo.empty(), bd("1.50"));

        AgentVerdict v = agent.evaluate(playbook, context);

        assertTrue(v.reasoning().contains("DXY"));
    }

    // ── Session Timing Agent ────────────────────────────────────────────

    @Test
    void sessionAgent_killZone_highConfidence() {
        var agent = new SessionTimingAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var session = new AgentContext.SessionInfo("NY_AM", true, true, false);
        var context = contextWith(null, null, session);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.HIGH, v.confidence());
        assertTrue(v.reasoning().contains("KILL ZONE"));
    }

    @Test
    void sessionAgent_asianSession_lowLiquidity() {
        var agent = new SessionTimingAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var session = new AgentContext.SessionInfo("ASIAN", false, true, false);
        var context = contextWith(null, null, session);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.LOW, v.confidence());
        assertTrue(v.reasoning().contains("LOW LIQUIDITY"));
    }

    @Test
    void sessionAgent_marketClosed_blocked() {
        var agent = new SessionTimingAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var session = new AgentContext.SessionInfo("CLOSED", false, false, false);
        var context = contextWith(null, null, session);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertTrue((boolean) v.adjustments().get("blocked"));
    }

    // ── Zone Quality Agent ──────────────────────────────────────────────

    @Test
    void zoneAgent_clearPath_highConfidence() {
        var agent = new ZoneQualityAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var context = contextWith(null, null, null);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.HIGH, v.confidence());
    }

    @Test
    void zoneAgent_breakerZone_strengthDetected() {
        var agent = new ZoneQualityAgent();
        var breakerSetup = new SetupCandidate(
            SetupType.ZONE_RETEST, "Breaker 97.31-98.38",
            bd("98.38"), bd("97.31"), bd("97.84"),
            0.5, true, false, true, 3.5, 6
        );
        var plan = new PlaybookPlan(bd("97.84"), bd("97.14"), bd("100.28"), bd("105.50"),
            3.5, 0.01, "Below breaker", "First opposing OB");
        var playbook = new PlaybookEvaluation(
            new FilterResult(true, "BULLISH", Direction.LONG, true, 6, 0, 6, 1.0, true, VaPosition.BELOW_VA, true),
            List.of(breakerSetup), breakerSetup, plan, List.of(), 6, "LONG — ZONE RETEST — 6/7", Instant.now()
        );
        var context = contextWith(null, null, null);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertTrue(v.reasoning().contains("Breaker") || v.confidence() == Confidence.HIGH);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static BigDecimal bd(String val) { return new BigDecimal(val); }

    private static PlaybookInput minimalInput() {
        return new PlaybookInput(
            "BULLISH", "BULLISH", bd("99.49"), bd("95.26"), bd("97.00"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            null, null, null, "BUYING", bd("0.60"), null, null, bd("1.50")
        );
    }

    private static PlaybookEvaluation mockPlaybook(Direction direction, int score) {
        var setup = new SetupCandidate(
            SetupType.ZONE_RETEST, "OB BULLISH 91.03-94.71",
            bd("94.71"), bd("91.03"), bd("92.87"),
            1.0, true, false, true, 3.5, score
        );
        var plan = new PlaybookPlan(bd("92.87"), bd("90.50"), bd("97.00"), bd("99.49"),
            2.5, 0.01, "Below zone + ATR", "First opposing OB");
        return new PlaybookEvaluation(
            new FilterResult(true, "BULLISH", direction, true, 6, 0, 6, 1.0, true, VaPosition.BELOW_VA, true),
            List.of(setup), setup, plan, List.of(), score, direction + " — ZONE RETEST — " + score + "/7", Instant.now()
        );
    }

    private static PlaybookEvaluation noSetupPlaybook() {
        return new PlaybookEvaluation(
            new FilterResult(true, "BULLISH", Direction.LONG, true, 0, 0, 0, 1.0, true, VaPosition.BELOW_VA, true),
            List.of(), null, null, List.of(), 0, "NO TRADE", Instant.now()
        );
    }

    private AgentContext contextWith(AgentContext.MtfSnapshot mtf,
                                      AgentContext.MomentumSnapshot momentum,
                                      AgentContext.SessionInfo session) {
        return new AgentContext(
            Instrument.MCL, "10m", minimalInput(),
            AgentContext.PortfolioState.empty(),
            AgentContext.MacroSnapshot.empty(),
            mtf != null ? mtf : AgentContext.MtfSnapshot.empty(),
            momentum != null ? momentum : AgentContext.MomentumSnapshot.empty(),
            session != null ? session : AgentContext.SessionInfo.empty(),
            bd("1.50")
        );
    }

    private AgentContext contextWithPortfolio(double pnl, double drawdown, int positions,
                                               boolean correlated, double margin) {
        var portfolio = new AgentContext.PortfolioState(pnl, drawdown, positions, correlated, margin);
        return new AgentContext(
            Instrument.MCL, "10m", minimalInput(), portfolio,
            AgentContext.MacroSnapshot.empty(),
            AgentContext.MtfSnapshot.empty(),
            AgentContext.MomentumSnapshot.empty(),
            AgentContext.SessionInfo.empty(),
            bd("1.50")
        );
    }
}
