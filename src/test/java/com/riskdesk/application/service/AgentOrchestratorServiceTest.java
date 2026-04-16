package com.riskdesk.application.service;

import com.riskdesk.domain.engine.playbook.agent.*;
import com.riskdesk.domain.engine.playbook.event.AgentDecisionEvent;
import com.riskdesk.domain.engine.playbook.model.*;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Focused tests on the two behaviours PR #8 introduces:
 * <ol>
 *   <li><b>Gate short-circuit</b> — a blocking {@link Gate} must prevent any
 *       {@link Scorer} from being invoked. This is where the cost/latency
 *       savings live: no wasted Gemini calls on setups the session filter
 *       already rejected.</li>
 *   <li><b>Event publication</b> — every {@code orchestrate()} call must emit
 *       exactly one {@link AgentDecisionEvent}, regardless of eligibility.</li>
 * </ol>
 *
 * <p>Deliberately bypasses the data-building helpers by passing a pre-built
 * {@link AgentContext}. The orchestrator has ~9 infra dependencies we do not
 * need for the orchestrate() path — we pass {@code null} for ones
 * {@link AgentOrchestratorService#orchestrate} never touches.
 */
class AgentOrchestratorServiceTest {

    private ExecutorService exec;
    private RecordingPublisher publisher;
    private RiskManagementService risk;

    @BeforeEach
    void setUp() {
        exec = Executors.newFixedThreadPool(2);
        publisher = new RecordingPublisher();
        risk = mock(RiskManagementService.class);
        when(risk.evaluate(any(), any()))
            .thenReturn(RiskManagementService.RiskGateVerdict.eligible(0.01, List.of()));
    }

    @AfterEach
    void tearDown() {
        exec.shutdownNow();
    }

    @Test
    void gateBlocks_scorersAreNotInvoked_andEventIsPublished() {
        CountingScorer scorer = new CountingScorer();
        AgentOrchestratorService svc = newOrchestrator(List.of(new BlockingGate(), scorer));

        FinalVerdict fv = svc.orchestrate(samplePlaybook(), sampleContext());

        // Gate blocked — no scorer should have been called.
        assertEquals(0, scorer.calls.get(),
            "Scorer must be skipped when a Gate hard-blocks");
        assertEquals("BLOCKED", fv.eligibility());
        assertEquals(1, publisher.events.size(),
            "Exactly one AgentDecisionEvent per orchestrate()");
        AgentDecisionEvent ev = publisher.events.get(0);
        assertEquals("BLOCKED", ev.eligibility());
        // Only the gate verdict made it into the decision event.
        assertEquals(1, ev.verdicts().size());
        assertEquals("Blocking-Gate", ev.verdicts().get(0).agentName());
        assertTrue(ev.verdicts().get(0).blocked());
    }

    @Test
    void gatesPass_allScorersRun_andEventCarriesAllVerdicts() {
        CountingScorer s1 = new CountingScorer();
        CountingScorer s2 = new CountingScorer();
        AgentOrchestratorService svc = newOrchestrator(
            List.of(new PassingGate(), s1, s2));

        FinalVerdict fv = svc.orchestrate(samplePlaybook(), sampleContext());

        assertEquals(1, s1.calls.get(), "Scorer 1 must be called when all gates pass");
        assertEquals(1, s2.calls.get(), "Scorer 2 must be called when all gates pass");
        // gate + 2 scorers = 3 verdicts in the final fan-in
        assertEquals(3, fv.agentVerdicts().size());
        assertEquals(1, publisher.events.size());
        AgentDecisionEvent ev = publisher.events.get(0);
        assertEquals(3, ev.verdicts().size());
    }

    @Test
    void noSetup_shortCircuitsWithEvent_withoutCallingAnyAgent() {
        CountingScorer scorer = new CountingScorer();
        AgentOrchestratorService svc = newOrchestrator(List.of(new PassingGate(), scorer));

        FinalVerdict fv = svc.orchestrate(noSetupPlaybook(), sampleContext());

        assertEquals(0, scorer.calls.get(),
            "Empty setup must skip every agent");
        assertEquals("INELIGIBLE", fv.eligibility());
        assertEquals(1, publisher.events.size());
    }

    @Test
    void riskGateBlocks_noAgentIsInvoked_andEventCarriesBlock() {
        when(risk.evaluate(any(), any())).thenReturn(
            RiskManagementService.RiskGateVerdict.blocked("DAILY DRAWDOWN", List.of("DD breach")));
        CountingScorer scorer = new CountingScorer();
        AgentOrchestratorService svc = newOrchestrator(List.of(new PassingGate(), scorer));

        FinalVerdict fv = svc.orchestrate(samplePlaybook(), sampleContext());

        assertEquals(0, scorer.calls.get());
        assertEquals("BLOCKED", fv.eligibility());
        assertEquals(1, publisher.events.size());
        assertEquals("BLOCKED", publisher.events.get(0).eligibility());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private AgentOrchestratorService newOrchestrator(List<TradingAgent> agents) {
        // Upstream infra deps that orchestrate() does not touch can be null —
        // buildContext() is the only consumer, and we pass a pre-built context.
        return new AgentOrchestratorService(
            agents, risk, null, null, null, null, null, null, exec, publisher);
    }

    private static AgentContext sampleContext() {
        return new AgentContext(
            Instrument.MCL, "10m",
            new PlaybookInput("BULLISH", "BULLISH",
                new BigDecimal("99.49"), new BigDecimal("95.26"), new BigDecimal("97.00"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, "BUYING", new BigDecimal("0.60"), null, null, new BigDecimal("1.50")),
            AgentContext.PortfolioState.empty(),
            AgentContext.MacroSnapshot.empty(),
            AgentContext.MtfSnapshot.empty(),
            AgentContext.MomentumSnapshot.empty(),
            AgentContext.SessionInfo.empty(),
            new BigDecimal("1.50"),
            AgentContext.OrderFlowSnapshot.empty(),
            AgentContext.DepthSnapshot.empty(),
            AgentContext.AbsorptionSnapshot.empty(),
            AgentContext.VolumeProfileSnapshot.empty(),
            AgentContext.ZoneQualitySnapshot.empty()
        );
    }

    private static PlaybookEvaluation samplePlaybook() {
        var setup = new SetupCandidate(
            SetupType.ZONE_RETEST, "OB BULLISH 91-94",
            new BigDecimal("94"), new BigDecimal("91"), new BigDecimal("92"),
            1.0, true, false, true, 3.5, 6);
        var plan = new PlaybookPlan(
            new BigDecimal("92"), new BigDecimal("90"),
            new BigDecimal("97"), new BigDecimal("99"),
            2.5, 0.01, "below zone", "next OB");
        return new PlaybookEvaluation(
            new FilterResult(true, "BULLISH", Direction.LONG, true, 6, 0, 6, 1.0, true,
                VaPosition.BELOW_VA, true),
            List.of(setup), setup, plan, List.of(), 6,
            "LONG — ZONE RETEST — 6/7", Instant.now()
        );
    }

    private static PlaybookEvaluation noSetupPlaybook() {
        return new PlaybookEvaluation(
            new FilterResult(true, "BULLISH", Direction.LONG, true, 0, 0, 0, 1.0, true,
                VaPosition.BELOW_VA, true),
            List.of(), null, null, List.of(), 0, "NO TRADE", Instant.now()
        );
    }

    // ── Stub agents ──────────────────────────────────────────────────────

    private static final class BlockingGate implements Gate {
        @Override public String name() { return "Blocking-Gate"; }
        @Override public AgentVerdict evaluate(PlaybookEvaluation p, AgentContext c) {
            return new AgentVerdict(name(), Confidence.LOW, Direction.LONG,
                "stub block", AgentAdjustments.block());
        }
    }

    private static final class PassingGate implements Gate {
        @Override public String name() { return "Passing-Gate"; }
        @Override public AgentVerdict evaluate(PlaybookEvaluation p, AgentContext c) {
            return new AgentVerdict(name(), Confidence.HIGH, Direction.LONG,
                "ok", AgentAdjustments.none());
        }
    }

    private static final class CountingScorer implements Scorer {
        final AtomicInteger calls = new AtomicInteger();
        @Override public String name() { return "Counting-Scorer-" + System.identityHashCode(this); }
        @Override public AgentVerdict evaluate(PlaybookEvaluation p, AgentContext c) {
            calls.incrementAndGet();
            return new AgentVerdict(name(), Confidence.HIGH, Direction.LONG,
                "ok", AgentAdjustments.none());
        }
    }

    /** Minimal {@link ApplicationEventPublisher} that captures everything published. */
    private static final class RecordingPublisher implements ApplicationEventPublisher {
        final List<AgentDecisionEvent> events = new CopyOnWriteArrayList<>();
        @Override public void publishEvent(Object event) {
            if (event instanceof AgentDecisionEvent ade) events.add(ade);
        }
    }
}
