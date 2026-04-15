package com.riskdesk.domain.engine.playbook;

import com.riskdesk.domain.engine.playbook.agent.*;
import com.riskdesk.domain.engine.playbook.agent.port.AgentAiRequest;
import com.riskdesk.domain.engine.playbook.agent.port.AgentAiResponse;
import com.riskdesk.domain.engine.playbook.agent.port.GeminiAgentPort;
import com.riskdesk.domain.engine.playbook.model.*;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the post-refactor agent topology:
 * <ul>
 *   <li>1 rule-based agent: {@link SessionTimingAgent}</li>
 *   <li>3 Gemini-powered agents: {@link MtfConfluenceAIAgent}, {@link OrderFlowAIAgent},
 *       {@link ZoneQualityAIAgent} — each tested with a canned {@link GeminiAgentPort}
 *       plus a rule-based fallback when the port says {@code aiAvailable=false}.</li>
 * </ul>
 */
class AgentTest {

    // ── Session Timing (rule-based) ───────────────────────────────────────

    @Test
    void sessionAgent_killZone_highConfidence() {
        var agent = new SessionTimingAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var session = new AgentContext.SessionInfo("NY_AM", true, true, false);
        var context = contextWithSession(session);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.HIGH, v.confidence());
        assertTrue(v.reasoning().contains("KILL ZONE"));
    }

    @Test
    void sessionAgent_asianSession_lowLiquidity() {
        var agent = new SessionTimingAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var session = new AgentContext.SessionInfo("ASIAN", false, true, false);
        var context = contextWithSession(session);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertEquals(Confidence.LOW, v.confidence());
        assertTrue(v.reasoning().contains("LOW LIQUIDITY"));
    }

    @Test
    void sessionAgent_marketClosed_blocked() {
        var agent = new SessionTimingAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var session = new AgentContext.SessionInfo("CLOSED", false, false, false);
        var context = contextWithSession(session);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertTrue(v.adjustments().blocked());
    }

    @Test
    void sessionAgent_maintenanceWindow_blocked() {
        var agent = new SessionTimingAgent();
        var playbook = mockPlaybook(Direction.LONG, 6);
        var session = new AgentContext.SessionInfo("NY_AM", false, true, true);
        var context = contextWithSession(session);

        AgentVerdict v = agent.evaluate(playbook, context);

        assertTrue(v.adjustments().blocked());
        assertTrue(v.reasoning().contains("MAINTENANCE"));
    }

    // ── MTF Confluence AI ────────────────────────────────────────────────

    @Test
    void mtfAiAgent_geminiHigh_returnsHigh() {
        var port = cannedPort(AgentAiResponse.HIGH, "Triple HTF confluence", Map.of("mtf_alignment", 3));
        var agent = new MtfConfluenceAIAgent(port, "test-prompt");
        var mtf = new AgentContext.MtfSnapshot(
            "BULLISH", "BULLISH", "BULLISH", "BULLISH",
            "BOS", "BOS", 80.0, true, 75.0, true);
        var ctx = contextWithMtf(mtf);

        AgentVerdict v = agent.evaluate(mockPlaybook(Direction.LONG, 6), ctx);

        assertEquals(Confidence.HIGH, v.confidence());
        assertEquals("Triple HTF confluence", v.reasoning());
    }

    @Test
    void mtfAiAgent_geminiUnavailable_fallsBackToAlignmentScore() {
        // Port returns fallback → agent must run its own rule-based rule on alignmentScore
        var port = fallbackPort();
        var agent = new MtfConfluenceAIAgent(port, "test-prompt");
        // 2/3 HTF aligned with LONG (H1 + H4 BULLISH, Daily BEARISH)
        var mtf = new AgentContext.MtfSnapshot(
            "BULLISH", "BULLISH", "BULLISH", "BEARISH",
            "BOS", "BOS", null, null, null, null);
        var ctx = contextWithMtf(mtf);

        AgentVerdict v = agent.evaluate(mockPlaybook(Direction.LONG, 6), ctx);

        assertEquals(Confidence.HIGH, v.confidence());
        assertTrue(v.reasoning().contains("AI unavailable"));
        assertEquals(2, ((Number) v.adjustments().extraFlags().get("mtf_alignment")).intValue());
    }

    @Test
    void mtfAiAgent_skipWhenNoSetup() {
        var agent = new MtfConfluenceAIAgent(cannedPort(AgentAiResponse.HIGH, "", Map.of()), "test-prompt");

        AgentVerdict v = agent.evaluate(noSetupPlaybook(), contextWithMtf(null));

        assertTrue(v.reasoning().contains("No setup"));
    }

    @Test
    void mtfAiAgent_skipWhenNoMtfData() {
        var agent = new MtfConfluenceAIAgent(cannedPort(AgentAiResponse.HIGH, "", Map.of()), "test-prompt");
        var ctx = new AgentContext(
            Instrument.MCL, "10m", minimalInput(),
            AgentContext.PortfolioState.empty(),
            AgentContext.MacroSnapshot.empty(),
            null,   // no MTF
            AgentContext.MomentumSnapshot.empty(),
            AgentContext.SessionInfo.empty(),
            bd("1.50"),
            AgentContext.OrderFlowSnapshot.empty(),
            AgentContext.DepthSnapshot.empty(),
            AgentContext.AbsorptionSnapshot.empty(),
            AgentContext.VolumeProfileSnapshot.empty(),
            AgentContext.ZoneQualitySnapshot.empty()
        );

        AgentVerdict v = agent.evaluate(mockPlaybook(Direction.LONG, 6), ctx);

        assertTrue(v.reasoning().contains("No MTF"));
    }

    // ── Order Flow AI ────────────────────────────────────────────────────

    @Test
    void orderFlowAiAgent_geminiHigh_passesThrough() {
        var port = cannedPort(AgentAiResponse.HIGH,
            "REAL_TICKS + bullish absorption", Map.of("data_quality", "real_ticks", "flow_supports", true));
        var agent = new OrderFlowAIAgent(port, "test-prompt");
        var flow = new AgentContext.OrderFlowSnapshot(
            "REAL_TICKS", 700, 300, 400, 3_500, 70.0, "RISING", false, null);
        var absorption = new AgentContext.AbsorptionSnapshot(
            true, "BULLISH_ABSORPTION", 2.4, 1.0, 12_000);
        var ctx = contextWithOrderFlow(flow, absorption);

        AgentVerdict v = agent.evaluate(mockPlaybook(Direction.LONG, 6), ctx);

        assertEquals(Confidence.HIGH, v.confidence());
        assertEquals(Boolean.TRUE, v.adjustments().extraFlags().get("flow_supports"));
    }

    @Test
    void orderFlowAiAgent_geminiUnavailable_flowAlignedWithAbsorption_fallbackHigh() {
        var port = fallbackPort();
        var agent = new OrderFlowAIAgent(port, "test-prompt");
        var flow = new AgentContext.OrderFlowSnapshot(
            "REAL_TICKS", 700, 300, 400, 3_500, 70.0, "RISING", false, null);
        var absorption = new AgentContext.AbsorptionSnapshot(
            true, "BULLISH_ABSORPTION", 2.4, 1.0, 12_000);
        var ctx = contextWithOrderFlow(flow, absorption);

        AgentVerdict v = agent.evaluate(mockPlaybook(Direction.LONG, 6), ctx);

        assertEquals(Confidence.HIGH, v.confidence());
        assertTrue(v.reasoning().contains("AI unavailable"));
    }

    @Test
    void orderFlowAiAgent_geminiUnavailable_flowMisaligned_fallbackLow() {
        var port = fallbackPort();
        var agent = new OrderFlowAIAgent(port, "test-prompt");
        // Bearish flow + bearish absorption against a LONG
        var flow = new AgentContext.OrderFlowSnapshot(
            "REAL_TICKS", 200, 800, -600, -4_000, 20.0, "FALLING", true, "BEARISH_DIVERGENCE");
        var absorption = new AgentContext.AbsorptionSnapshot(
            true, "BEARISH_ABSORPTION", 2.8, 0.5, 11_000);
        var ctx = contextWithOrderFlow(flow, absorption);

        AgentVerdict v = agent.evaluate(mockPlaybook(Direction.LONG, 6), ctx);

        assertEquals(Confidence.LOW, v.confidence());
    }

    // ── Zone Quality AI ──────────────────────────────────────────────────

    @Test
    void zoneAiAgent_geminiHigh_returnsHigh() {
        var port = cannedPort(AgentAiResponse.HIGH,
            "OB defended, live score 85, 0 obstacles", Map.of("weak_zone", false));
        var agent = new ZoneQualityAIAgent(port, "test-prompt");
        var zq = new AgentContext.ZoneQualitySnapshot(
            85.0, 85.0, Boolean.TRUE, 2.5, 80.0, 75.0, Boolean.TRUE, 70.0);
        var ctx = contextWithZoneQuality(zq);

        AgentVerdict v = agent.evaluate(mockPlaybook(Direction.LONG, 6), ctx);

        assertEquals(Confidence.HIGH, v.confidence());
    }

    @Test
    void zoneAiAgent_geminiUnavailable_weakZone_fallbackLow() {
        var port = fallbackPort();
        var agent = new ZoneQualityAIAgent(port, "test-prompt");
        var zq = new AgentContext.ZoneQualitySnapshot(
            20.0, 20.0, Boolean.FALSE, 0.3, null, null, null, null);
        var ctx = contextWithZoneQuality(zq);

        AgentVerdict v = agent.evaluate(mockPlaybook(Direction.LONG, 6), ctx);

        assertEquals(Confidence.LOW, v.confidence());
        assertTrue(v.reasoning().contains("AI unavailable"));
    }

    @Test
    void zoneAiAgent_geminiUnavailable_highQuality_fallbackHigh() {
        var port = fallbackPort();
        var agent = new ZoneQualityAIAgent(port, "test-prompt");
        // High quality OB + no obstacles (empty lists in input) → HIGH
        var zq = new AgentContext.ZoneQualitySnapshot(
            85.0, 85.0, Boolean.TRUE, 2.5, null, null, null, null);
        var ctx = contextWithZoneQuality(zq);

        AgentVerdict v = agent.evaluate(mockPlaybook(Direction.LONG, 6), ctx);

        assertEquals(Confidence.HIGH, v.confidence());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static BigDecimal bd(String val) { return new BigDecimal(val); }

    /** A {@link GeminiAgentPort} that always returns a canned response as if AI is up. */
    private static GeminiAgentPort cannedPort(String confidence, String reasoning,
                                              Map<String, Object> flags) {
        return new GeminiAgentPort() {
            @Override
            public AgentAiResponse analyze(AgentAiRequest request) {
                return new AgentAiResponse(confidence, reasoning, flags, true);
            }
        };
    }

    /** A {@link GeminiAgentPort} that reports the port is unavailable — agents must fall back. */
    private static GeminiAgentPort fallbackPort() {
        return new GeminiAgentPort() {
            @Override
            public AgentAiResponse analyze(AgentAiRequest request) {
                return AgentAiResponse.fallback("test: port unavailable");
            }
        };
    }

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
            List.of(setup), setup, plan, List.of(), score,
            direction + " — ZONE RETEST — " + score + "/7", Instant.now()
        );
    }

    private static PlaybookEvaluation noSetupPlaybook() {
        return new PlaybookEvaluation(
            new FilterResult(true, "BULLISH", Direction.LONG, true, 0, 0, 0, 1.0, true, VaPosition.BELOW_VA, true),
            List.of(), null, null, List.of(), 0, "NO TRADE", Instant.now()
        );
    }

    private static AgentContext contextWithSession(AgentContext.SessionInfo session) {
        return new AgentContext(
            Instrument.MCL, "10m", minimalInput(),
            AgentContext.PortfolioState.empty(),
            AgentContext.MacroSnapshot.empty(),
            AgentContext.MtfSnapshot.empty(),
            AgentContext.MomentumSnapshot.empty(),
            session,
            bd("1.50"),
            AgentContext.OrderFlowSnapshot.empty(),
            AgentContext.DepthSnapshot.empty(),
            AgentContext.AbsorptionSnapshot.empty(),
            AgentContext.VolumeProfileSnapshot.empty(),
            AgentContext.ZoneQualitySnapshot.empty()
        );
    }

    private static AgentContext contextWithMtf(AgentContext.MtfSnapshot mtf) {
        return new AgentContext(
            Instrument.MCL, "10m", minimalInput(),
            AgentContext.PortfolioState.empty(),
            AgentContext.MacroSnapshot.empty(),
            mtf != null ? mtf : AgentContext.MtfSnapshot.empty(),
            AgentContext.MomentumSnapshot.empty(),
            AgentContext.SessionInfo.empty(),
            bd("1.50"),
            AgentContext.OrderFlowSnapshot.empty(),
            AgentContext.DepthSnapshot.empty(),
            AgentContext.AbsorptionSnapshot.empty(),
            AgentContext.VolumeProfileSnapshot.empty(),
            AgentContext.ZoneQualitySnapshot.empty()
        );
    }

    private static AgentContext contextWithOrderFlow(AgentContext.OrderFlowSnapshot flow,
                                                     AgentContext.AbsorptionSnapshot absorption) {
        return new AgentContext(
            Instrument.MCL, "10m", minimalInput(),
            AgentContext.PortfolioState.empty(),
            AgentContext.MacroSnapshot.empty(),
            AgentContext.MtfSnapshot.empty(),
            AgentContext.MomentumSnapshot.empty(),
            AgentContext.SessionInfo.empty(),
            bd("1.50"),
            flow,
            AgentContext.DepthSnapshot.empty(),
            absorption,
            AgentContext.VolumeProfileSnapshot.empty(),
            AgentContext.ZoneQualitySnapshot.empty()
        );
    }

    private static AgentContext contextWithZoneQuality(AgentContext.ZoneQualitySnapshot zq) {
        return new AgentContext(
            Instrument.MCL, "10m", minimalInput(),
            AgentContext.PortfolioState.empty(),
            AgentContext.MacroSnapshot.empty(),
            AgentContext.MtfSnapshot.empty(),
            AgentContext.MomentumSnapshot.empty(),
            AgentContext.SessionInfo.empty(),
            bd("1.50"),
            AgentContext.OrderFlowSnapshot.empty(),
            AgentContext.DepthSnapshot.empty(),
            AgentContext.AbsorptionSnapshot.empty(),
            AgentContext.VolumeProfileSnapshot.empty(),
            zq
        );
    }
}
